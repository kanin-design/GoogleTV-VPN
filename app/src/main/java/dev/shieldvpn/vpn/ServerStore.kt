package dev.shieldvpn.vpn

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.Features
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * Servers live in filesDir/servers: one .ovpn file each, kept as imported, and an
 * index with the address, port and protocol, which the user can change in Advanced.
 * A WireGuard server is a .conf file without its keys, which [CredentialStore] keeps; the
 * stores keep these whatever the build, and only WireGuard builds show them ([hidden]).
 * Both are replaced whole on each change ([writeAtomically]).
 */
object ServerStore {

    private const val INDEX = "index.json"
    private const val MAX_BYTES = 256 * 1024
    private val WHITESPACE = Regex("\\s+")
    private val REMOTE_OPTIONS = setOf("remote", "proto", "port")
    private val INLINE_LOGIN = Regex("<auth-user-pass>([\\s\\S]*?)</auth-user-pass>")
    private val SECRET_BLOCK = Regex("<(key|tls-auth|tls-crypt|tls-crypt-v2|secret|pkcs12)>[\\s\\S]*?</\\1>")
    private val CERT_BLOCK = Regex("<cert>([\\s\\S]*?)</cert>")

    // A valid key, all zeros, put in a stored WireGuard file only to read the rest of it.
    private const val PLACEHOLDER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    // The last index read, for a file that later turns out damaged.
    private var lastGood: List<Server>? = null

    // What each file says, by server id, with the file's size and time to tell when it changed.
    private val infos = HashMap<String, Pair<Long, ConfigInfo>>()

    @Synchronized
    fun list(context: Context): List<Server> {
        val index = File(dir(context), INDEX)
        if (!index.exists()) return emptyList()
        return runCatching { parse(readAtomically(index).orEmpty()) }
            .onSuccess { lastGood = it }
            .getOrElse { setAside(index, it); lastGood ?: emptyList() }
    }

    private fun parse(text: String): List<Server> {
        val array = JSONArray(text)
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            Server(
                id = item.getString("id"),
                host = item.getString("host"),
                port = item.getInt("port"),
                protocol = Protocol.valueOf(item.getString("protocol")),
                kind = if (item.optString("kind") == "wireguard") VpnKind.WireGuard else VpnKind.OpenVpn,
            )
        }
    }

    /** Stores [config] as a server. [id] is passed only to keep an existing id. */
    fun add(context: Context, config: String, id: String = UUID.randomUUID().toString()): Server {
        val server = if (WireGuardFile.looksLike(config)) {
            val (host, port) = WireGuardFile.endpoint(config) ?: ("" to 51820)
            Server(id, host, port, Protocol.UDP, VpnKind.WireGuard)
        } else {
            val (host, port, protocol) = remoteOf(config) ?: Triple("", 1194, Protocol.UDP)
            Server(id, host, port, protocol)
        }
        writeAtomically(fileFor(context, id, server.kind), config)
        synchronized(this) { infos.remove(id) }
        update(context, server)
        return server
    }

    /** Saves a changed address, port or protocol, keeping the server's place in the list. */
    @Synchronized
    fun update(context: Context, server: Server) {
        val servers = list(context)
        write(
            context,
            if (servers.any { it.id == server.id }) servers.map { if (it.id == server.id) server else it } else servers + server,
        )
    }

    /** Goes back to the address, port and protocol written in the file. */
    fun resetToFile(context: Context, id: String) {
        val raw = rawText(context, id) ?: return
        if (WireGuardFile.looksLike(raw)) {
            val (host, port) = WireGuardFile.endpoint(raw) ?: return
            return update(context, Server(id, host, port, Protocol.UDP, VpnKind.WireGuard))
        }
        val (host, port, protocol) = remoteOf(raw) ?: return
        update(context, Server(id, host, port, protocol))
    }

    /** A WireGuard profile in a build without WireGuard: kept, but not shown or connected. */
    fun hidden(context: Context, serverId: String): Boolean =
        !Features.WIREGUARD && list(context).firstOrNull { it.id == serverId }?.kind == VpnKind.WireGuard

    @Synchronized
    fun delete(context: Context, id: String) {
        VpnKind.entries.forEach { fileFor(context, id, it).delete() }
        infos.remove(id)
        write(context, list(context).filterNot { it.id == id })
    }

    @Synchronized
    fun clear(context: Context) {
        dir(context).deleteRecursively()
        lastGood = null
        infos.clear()
    }

    /** The config to connect with: the file as imported, with the address the user set in Advanced. */
    fun effectiveConfig(context: Context, id: String): String? {
        val raw = rawText(context, id) ?: return null
        val server = list(context).firstOrNull { it.id == id } ?: return raw
        if (server.kind == VpnKind.WireGuard) {
            return if (WireGuardFile.endpoint(raw) == server.host to server.port) raw else WireGuardFile.withEndpoint(raw, server.host, server.port)
        }
        if (remoteOf(raw) == Triple(server.host, server.port, server.protocol)) return raw
        // A <connection> block carries its own remote lines, so such files are used as they are.
        if ("<connection>" in raw) return raw
        val kept = raw.lineSequence().filterNot { it.trim().split(WHITESPACE).first() in REMOTE_OPTIONS }
        return "remote ${server.host} ${server.port} ${server.protocol.token}\n" + kept.joinToString("\n")
    }

    /** The file as it is used, with the server set in Advanced and private keys hidden, for the read-only view. */
    fun displayConfig(context: Context, id: String): String =
        effectiveConfig(context, id)?.let(::hideSecrets) ?: "The server file is missing."

    fun hideSecrets(config: String): String =
        if (WireGuardFile.looksLike(config)) WireGuardFile.hideSecrets(config)
        else config.replace(SECRET_BLOCK) { "<${it.groupValues[1]}>\n(hidden)\n</${it.groupValues[1]}>" }

    /** What the file says. Read again only when the file changed, since this runs on each refresh. */
    @Synchronized
    fun info(context: Context, id: String): ConfigInfo? {
        val file = fileOf(context, id)
        if (!file.exists()) return null
        val stamp = file.lastModified() * 31 + file.length()
        infos[id]?.takeIf { it.first == stamp }?.let { return it.second }
        return rawText(context, id)?.let(::infoOf)?.also { infos[id] = stamp to it }
    }

    /** The file as stored, secrets included. */
    fun rawConfig(context: Context, id: String): String? = rawText(context, id)

    /** Replaces the stored file, for example with a client certificate added to it. */
    @Synchronized
    fun replaceConfig(context: Context, id: String, config: String) {
        writeAtomically(fileOf(context, id), config)
        infos.remove(id)
    }

    fun hasCertificate(config: String): Boolean =
        "<ca>" in config || "<pkcs12>" in config || config.lineSequence().any { it.trim().startsWith("ca ") }

    /** The file carries its own client certificate, inline or as a cert or pkcs12 line. */
    fun hasClientCertificate(config: String): Boolean =
        "<cert>" in config || "<pkcs12>" in config ||
            config.lineSequence().map { it.trim().split(WHITESPACE).first() }.any { it == "cert" || it == "pkcs12" }

    fun needsLogin(config: String): Boolean =
        config.lineSequence().any { it.trim().startsWith("auth-user-pass") }

    /** "client-home.ovpn" becomes "Client home". */
    fun nameFromFile(fileName: String): String {
        val base = fileName.removeSuffix(".ovpn").removeSuffix(".conf").replace('-', ' ').replace('_', ' ').trim()
        return base.replaceFirstChar { it.uppercase() }.ifEmpty { "Profile" }
    }

    // ---- Importing --------------------------------------------------------------

    /** A file from a phone, a link or pasted text. */
    fun review(fileName: String, bytes: ByteArray): ImportReview {
        if (bytes.size > MAX_BYTES) return problem(fileName, "The file is too big to be an OpenVPN profile.")
        val text = String(bytes, Charsets.UTF_8)
        if (Features.WIREGUARD && WireGuardFile.looksLike(text)) return wireGuardReview(fileName, text)
        val (host, port, protocol) = remoteOf(text)
            ?: return problem(fileName, "The file has no remote line, so ${APP_NAME} can't tell which server to connect to. Pick another file, or ask whoever sent it for a new copy.")
        val match = INLINE_LOGIN.find(text)
        var config = text
        var username = ""
        var password = ""
        if (match != null) {
            // Move an inline login out of the file, so the password is only kept encrypted.
            val lines = match.groupValues[1].lines().map { it.trim() }.filter { it.isNotEmpty() }
            username = lines.getOrElse(0) { "" }
            password = lines.getOrElse(1) { "" }
            config = text.removeRange(match.range)
            if (!needsLogin(config)) config += "\nauth-user-pass\n"
        }
        return ImportReview(
            fileName = fileName,
            text = config,
            name = nameFromFile(fileName),
            host = host,
            port = port,
            protocol = protocol,
            needsLogin = needsLogin(config),
            username = username,
            password = password,
            warnings = infoOf(config).warnings,
        )
    }

    private fun wireGuardReview(fileName: String, text: String): ImportReview =
        when (val r = WireGuardFile.review(text)) {
            is WireGuardFile.Review.Problem -> problem(fileName, r.message)
            is WireGuardFile.Review.Ok -> ImportReview(
                fileName = fileName,
                text = r.text,
                name = nameFromFile(fileName),
                host = r.facts.host,
                port = r.facts.port,
                protocol = Protocol.UDP,
                needsLogin = false,
                username = "",
                password = "",
                warnings = r.warnings,
                kind = VpnKind.WireGuard,
                wg = r.facts,
                wgSecrets = r.secrets,
            )
        }

    private fun problem(fileName: String, why: String) =
        ImportReview(fileName, "", "", "", 0, Protocol.UDP, false, "", "", emptyList(), why)

    // ---- Reading a config -------------------------------------------------------

    private fun infoOf(raw: String): ConfigInfo {
        if (WireGuardFile.looksLike(raw)) return wireGuardInfo(raw)
        val lines = raw.lineSequence().map { it.trim() }.toList()
        fun words(key: String) = lines.firstOrNull { it.split(WHITESPACE).first() == key }?.split(WHITESPACE)
        val cipher = words("data-ciphers")?.getOrNull(1)?.split(':')?.firstOrNull() ?: words("cipher")?.getOrNull(1)
        val tls = when {
            "<tls-crypt-v2>" in raw || words("tls-crypt-v2") != null -> "tls-crypt-v2"
            "<tls-crypt>" in raw || words("tls-crypt") != null -> "tls-crypt"
            "<tls-auth>" in raw || words("tls-auth") != null -> "tls-auth"
            else -> null
        }
        val remote = remoteOf(raw)
        val warnings = buildList {
            if (words("up") != null || words("down") != null) add("The up and down scripts in the file cannot run on this device.")
            if (words("comp-lzo") != null || words("compress") != null) add("The file asks for compression. ${APP_NAME} only accepts it from the server.")
        }
        return ConfigInfo(
            cipher = cipher,
            tls = tls,
            needsLogin = needsLogin(raw),
            clientCert = hasClientCertificate(raw),
            cert = certOf(raw),
            fileHost = remote?.first,
            filePort = remote?.second,
            fileProtocol = remote?.third,
            remotes = lines.count { it.split(WHITESPACE).first() == "remote" },
            warnings = warnings,
            hasCa = hasCertificate(raw),
            connectionBlocks = "<connection>" in raw,
        )
    }

    /** A WireGuard file in the terms the screens already use: no login, no certificate to check. */
    private fun wireGuardInfo(raw: String): ConfigInfo {
        // The stored file has no keys; a stand-in lets the review read the rest.
        val review = WireGuardFile.review(WireGuardFile.withSecrets(raw, WireGuardFile.Secrets(PLACEHOLDER_KEY)))
        val ok = review as? WireGuardFile.Review.Ok
        val endpoint = WireGuardFile.endpoint(raw)
        return ConfigInfo(
            cipher = "ChaCha20-Poly1305",
            tls = null,
            needsLogin = false,
            clientCert = false,
            cert = null,
            fileHost = endpoint?.first,
            filePort = endpoint?.second,
            fileProtocol = Protocol.UDP,
            remotes = ok?.facts?.peers ?: 0,
            warnings = ok?.warnings ?: listOf((review as WireGuardFile.Review.Problem).message),
            hasCa = true,
            kind = VpnKind.WireGuard,
            wg = ok?.facts,
        )
    }

    private fun certOf(raw: String): CertInfo? {
        val block = CERT_BLOCK.find(raw)?.groupValues?.get(1) ?: return null
        val begin = block.indexOf("-----BEGIN CERTIFICATE-----")
        val end = block.indexOf("-----END CERTIFICATE-----")
        if (begin < 0 || end < 0) return null
        val pem = block.substring(begin, end + "-----END CERTIFICATE-----".length)
        return runCatching {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
            val key = cert.publicKey
            val keyText = if (key is RSAPublicKey) "RSA ${key.modulus.bitLength()}" else key.algorithm
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
            val parts = digest.split(':')
            CertInfo(
                subject = cert.subjectX500Principal.name,
                issuer = cert.issuerX500Principal.name,
                validUntil = "Valid until " + SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(cert.notAfter),
                key = keyText,
                fingerprint = (parts.take(4) + "…" + parts.takeLast(2)).joinToString(":"),
            )
        }.getOrNull()
    }

    // ---- Storage -----------------------------------------------------------------

    private fun dir(context: Context): File =
        File(context.filesDir, "servers").apply { mkdirs() }

    private fun rawText(context: Context, id: String): String? =
        fileOf(context, id).takeIf { it.exists() }?.let(::readAtomically)

    private fun fileFor(context: Context, id: String, kind: VpnKind): File =
        File(dir(context), if (kind == VpnKind.WireGuard) "$id.conf" else "$id.ovpn")

    /** The server's file, whichever kind it is. */
    private fun fileOf(context: Context, id: String): File =
        fileFor(context, id, VpnKind.OpenVpn).takeIf { it.exists() } ?: fileFor(context, id, VpnKind.WireGuard)

    private fun write(context: Context, servers: List<Server>) {
        val array = JSONArray()
        servers.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("protocol", s.protocol.name)
                    // Only on WireGuard servers: older builds read the index without it.
                    .apply { if (s.kind == VpnKind.WireGuard) put("kind", "wireguard") },
            )
        }
        writeAtomically(File(dir(context), INDEX), array.toString())
        lastGood = servers
    }

    /** Host, port and protocol from the first `remote` line, filling gaps from `port` and `proto`. */
    private fun remoteOf(config: String): Triple<String, Int, Protocol>? {
        var host: String? = null
        var port: Int? = null
        var protocol: Protocol? = null
        var defaultPort: Int? = null
        var defaultProtocol: Protocol? = null
        config.lineSequence().map { it.trim().split(WHITESPACE) }.forEach { words ->
            when (words.first()) {
                "remote" -> if (host == null && words.size >= 2) {
                    host = words[1]
                    port = words.getOrNull(2)?.toIntOrNull()
                    protocol = words.getOrNull(3)?.let(::protocolOf)
                }
                "port" -> defaultPort = words.getOrNull(1)?.toIntOrNull()
                "proto" -> defaultProtocol = words.getOrNull(1)?.let(::protocolOf)
            }
        }
        val found = host ?: return null
        return Triple(found, port ?: defaultPort ?: 1194, protocol ?: defaultProtocol ?: Protocol.UDP)
    }

    private fun protocolOf(word: String): Protocol = if (word.startsWith("tcp")) Protocol.TCP else Protocol.UDP

    fun fileNameOf(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()

    /** Reads at most a little more than [MAX_BYTES], so a huge file cannot exhaust memory. */
    fun readLimited(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() <= MAX_BYTES) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
