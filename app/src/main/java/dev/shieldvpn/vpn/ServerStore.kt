package dev.shieldvpn.vpn

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.shieldvpn.APP_NAME
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
 */
object ServerStore {

    private const val INDEX = "index.json"
    private const val MAX_BYTES = 256 * 1024
    private val WHITESPACE = Regex("\\s+")
    private val REMOTE_OPTIONS = setOf("remote", "proto", "port")
    private val INLINE_LOGIN = Regex("<auth-user-pass>([\\s\\S]*?)</auth-user-pass>")
    private val SECRET_BLOCK = Regex("<(key|tls-auth|tls-crypt|tls-crypt-v2|secret|pkcs12)>[\\s\\S]*?</\\1>")
    private val CERT_BLOCK = Regex("<cert>([\\s\\S]*?)</cert>")

    fun list(context: Context): List<Server> {
        val index = File(dir(context), INDEX)
        if (!index.exists()) return emptyList()
        val array = JSONArray(index.readText())
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            Server(
                id = item.getString("id"),
                host = item.getString("host"),
                port = item.getInt("port"),
                protocol = Protocol.valueOf(item.getString("protocol")),
            )
        }
    }

    /** Stores [config] as a server. [id] is passed only to keep an existing id. */
    fun add(context: Context, config: String, id: String = UUID.randomUUID().toString()): Server {
        val (host, port, protocol) = remoteOf(config) ?: Triple("", 1194, Protocol.UDP)
        val server = Server(id, host, port, protocol)
        File(dir(context), "$id.ovpn").writeText(config)
        update(context, server)
        return server
    }

    /** Saves a changed address, port or protocol, keeping the server's place in the list. */
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
        val (host, port, protocol) = remoteOf(raw) ?: return
        update(context, Server(id, host, port, protocol))
    }

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.ovpn").delete()
        write(context, list(context).filterNot { it.id == id })
    }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** The config to connect with: the file as imported, with the address the user set in Advanced. */
    fun effectiveConfig(context: Context, id: String): String? {
        val raw = rawText(context, id) ?: return null
        val server = list(context).firstOrNull { it.id == id } ?: return raw
        if (remoteOf(raw) == Triple(server.host, server.port, server.protocol)) return raw
        // A <connection> block carries its own remote lines, so such files are used as they are.
        if ("<connection>" in raw) return raw
        val kept = raw.lineSequence().filterNot { it.trim().split(WHITESPACE).first() in REMOTE_OPTIONS }
        return "remote ${server.host} ${server.port} ${server.protocol.token}\n" + kept.joinToString("\n")
    }

    /** The file as imported, with private keys hidden, for the read-only view. */
    fun displayConfig(context: Context, id: String): String =
        rawText(context, id)?.let(::hideSecrets) ?: "The server file is missing."

    fun hideSecrets(config: String): String =
        config.replace(SECRET_BLOCK) { "<${it.groupValues[1]}>\n(hidden)\n</${it.groupValues[1]}>" }

    fun info(context: Context, id: String): ConfigInfo? = rawText(context, id)?.let(::infoOf)

    /** The file as stored, secrets included. */
    fun rawConfig(context: Context, id: String): String? = rawText(context, id)

    /** Replaces the stored file, for example with a client certificate added to it. */
    fun replaceConfig(context: Context, id: String, config: String) {
        File(dir(context), "$id.ovpn").writeText(config)
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

    /** Reads and checks a picked file without saving it. */
    fun review(context: Context, source: Uri): ImportReview {
        val fileName = fileNameOf(context, source)
        val bytes = context.contentResolver.openInputStream(source)?.use { readLimited(it) }
            ?: return problem(fileName, "${APP_NAME} could not open this file.")
        return review(fileName, bytes)
    }

    /** A file from a phone, a link or pasted text. */
    fun review(fileName: String, bytes: ByteArray): ImportReview {
        if (bytes.size > MAX_BYTES) return problem(fileName, "The file is too big to be an OpenVPN profile.")
        val text = String(bytes, Charsets.UTF_8)
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

    private fun problem(fileName: String, why: String) =
        ImportReview(fileName, "", "", "", 0, Protocol.UDP, false, "", "", emptyList(), why)

    // ---- Reading a config -------------------------------------------------------

    private fun infoOf(raw: String): ConfigInfo {
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
        File(dir(context), "$id.ovpn").takeIf { it.exists() }?.readText()

    private fun write(context: Context, servers: List<Server>) {
        val array = JSONArray()
        servers.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("protocol", s.protocol.name),
            )
        }
        File(dir(context), INDEX).writeText(array.toString())
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
