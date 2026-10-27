package dev.shieldvpn.vpn

import java.util.Base64

/**
 * WireGuard's own file format (wg-quick): an [Interface] with this device's key and addresses,
 * and a [Peer] for each server. Only builds made with -Pwireguard=true read these (see
 * [Features]); the stores keep them whatever the build.
 *
 * The keys never stay in the stored file: [review] takes them out, [CredentialStore] keeps them
 * encrypted, and [withSecrets] puts them back for the tunnel. Plain Kotlin, so it is tested on
 * the JVM (WireGuardFileTest).
 */
object WireGuardFile {

    /** What the file says, for the review and the profile's details. */
    data class Facts(
        val addresses: List<String>,
        val dns: List<String>,
        val searchDomains: List<String>,
        val allowedIps: List<String>,
        val peers: Int,
        val host: String,
        val port: Int,
        val keepalive: Int?,
        val mtu: Int?,
        /** Every address goes through the VPN: AllowedIPs holds 0.0.0.0/0. */
        val fullTunnel: Boolean,
    )

    /** This device's private key, and the extra key shared with each server that has one, by its public key. */
    data class Secrets(val privateKey: String, val presharedKeys: Map<String, String> = emptyMap()) {
        // So a log line or a crash report never carries the keys.
        override fun toString() = "Secrets(hidden)"
    }

    sealed interface Review {
        /** [text] is the file without its keys, which are in [secrets]. */
        data class Ok(val text: String, val facts: Facts, val secrets: Secrets, val warnings: List<String>) : Review {
            override fun toString() = "Review.Ok(${facts.host}:${facts.port})"
        }

        data class Problem(val message: String) : Review
    }

    private class Section(val name: String) {
        val values = mutableListOf<Pair<String, String>>()
        fun all(key: String) = values.filter { it.first == key }.flatMap { it.second.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        fun one(key: String) = values.lastOrNull { it.first == key }?.second
    }

    private val SECRET_KEYS = setOf("privatekey", "presharedkey")
    private val INTERFACE_KEYS = setOf("privatekey", "address", "dns", "listenport", "mtu", "includedapplications", "excludedapplications")
    private val PEER_KEYS = setOf("publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive")
    private val SCRIPT_KEYS = setOf("preup", "postup", "predown", "postdown", "table", "saveconfig", "fwmark")

    /** A line that is [Interface] on its own: OpenVPN files never have one, whatever they are named. */
    fun looksLike(text: String): Boolean =
        text.lineSequence().any { content(it).equals("[Interface]", ignoreCase = true) }

    fun review(text: String): Review {
        val sections = parse(text)
        val iface = sections.filter { it.name == "interface" }
        if (iface.size != 1) return Review.Problem("The file needs one [Interface] section, and has ${iface.size}.")
        val i = iface.single()
        val peers = sections.filter { it.name == "peer" }

        val privateKey = i.one("privatekey") ?: return Review.Problem("The file has no PrivateKey in [Interface], so it can’t sign in.")
        if (!isKey(privateKey)) return Review.Problem("The PrivateKey in [Interface] isn’t a valid key.")
        val addresses = i.all("address")
        if (addresses.isEmpty()) return Review.Problem("The file has no Address in [Interface].")
        addresses.firstOrNull { !isNetwork(it) }?.let { return Review.Problem("The Address $it in [Interface] isn’t an address.") }
        val mtu = i.one("mtu")?.let { it.toIntOrNull()?.takeIf { n -> n in 576..9000 } ?: return Review.Problem("The MTU in [Interface] isn’t a number from 576 to 9000.") }
        val dnsWords = i.all("dns")

        if (peers.isEmpty()) return Review.Problem("The file has no [Peer], so there is no server to connect to.")
        val psks = mutableMapOf<String, String>()
        var endpoint: Pair<String, Int>? = null
        var keepalive: Int? = null
        for ((n, p) in peers.withIndex()) {
            val where = if (peers.size == 1) "[Peer]" else "[Peer] ${n + 1}"
            val key = p.one("publickey") ?: return Review.Problem("The $where section has no PublicKey.")
            if (!isKey(key)) return Review.Problem("The PublicKey in $where isn’t a valid key.")
            p.one("presharedkey")?.let { psk ->
                if (!isKey(psk)) return Review.Problem("The PresharedKey in $where isn’t a valid key.")
                psks[key] = psk
            }
            p.all("allowedips").firstOrNull { !isNetwork(it) }?.let { return Review.Problem("The AllowedIPs entry $it in $where isn’t a network.") }
            p.one("endpoint")?.let { e ->
                val parsed = endpointOf(e) ?: return Review.Problem("The Endpoint $e in $where needs an address and a port, as in vpn.example.net:51820.")
                if (endpoint == null) endpoint = parsed
            }
            p.one("persistentkeepalive")?.let { k ->
                val secs = if (k.equals("off", ignoreCase = true)) 0 else k.toIntOrNull()?.takeIf { it in 0..65535 }
                    ?: return Review.Problem("The PersistentKeepalive in $where isn’t a number of seconds.")
                if (keepalive == null && secs > 0) keepalive = secs
            }
        }
        val (host, port) = endpoint ?: return Review.Problem("The file has no Endpoint, so there is no server to connect to. Ask whoever made it for a copy with one.")

        val allowed = peers.flatMap { it.all("allowedips") }
        val full = allowed.any { it == "0.0.0.0/0" }
        val warnings = buildList {
            val scripts = sections.flatMap { s -> s.values.map { it.first } }.filter { it in SCRIPT_KEYS }.distinct()
            if (scripts.isNotEmpty()) add("The file’s ${scripts.joinToString { label(it) }} lines are ignored: they can’t run on this device.")
            if (i.one("includedapplications") != null || i.one("excludedapplications") != null) add("The file’s app lists are ignored. Settings › Apps decides which apps use the VPN.")
            val unknown = sections.flatMap { s -> s.values.map { it.first }.filter { k -> k !in SCRIPT_KEYS && k !in (if (s.name == "peer") PEER_KEYS else INTERFACE_KEYS) } }.distinct()
            if (unknown.isNotEmpty()) add("Ignored: ${unknown.joinToString { label(it) }}.")
            if (peers.size > 1) add("The file has ${peers.size} servers. Server and port applies to the first.")
            if (!full && allowed.isNotEmpty()) add("Only ${allowed.filter { ':' !in it }.ifEmpty { allowed }.joinToString()} goes through the VPN. Everything else uses your normal connection.")
            if (allowed.isEmpty()) add("No AllowedIPs: nothing goes through the VPN.")
        }
        val facts = Facts(
            addresses = addresses,
            dns = dnsWords.filter(::isAddress),
            searchDomains = dnsWords.filterNot(::isAddress),
            allowedIps = allowed,
            peers = peers.size,
            host = host,
            port = port,
            keepalive = keepalive,
            mtu = mtu,
            fullTunnel = full,
        )
        return Review.Ok(strip(text), facts, Secrets(privateKey, psks), warnings)
    }

    /** The file without its PrivateKey and PresharedKey lines; comments and the rest stay as they were. */
    fun strip(text: String): String =
        text.lines().filterNot { keyOf(it) in SECRET_KEYS }.joinToString("\n")

    /** The stored file with its keys put back, for the tunnel. */
    fun withSecrets(text: String, secrets: Secrets): String {
        val out = mutableListOf<String>()
        var section = ""
        for (line in strip(text).lines()) {
            out += line
            val c = content(line)
            if (c.startsWith("[") && c.endsWith("]")) {
                section = c.substring(1, c.length - 1).trim().lowercase()
                if (section == "interface") out += "PrivateKey = ${secrets.privateKey}"
            } else if (section == "peer" && keyOf(line) == "publickey") {
                secrets.presharedKeys[valueOf(line)]?.let { out += "PresharedKey = $it" }
            }
        }
        return out.joinToString("\n")
    }

    /** One server in a file, as the tunnel needs it. */
    data class Peer(
        val publicKey: String,
        val presharedKey: String?,
        val host: String?,
        val port: Int?,
        val allowedIps: List<String>,
        val keepalive: Int?,
    ) {
        override fun toString() = "Peer($host:$port)"
    }

    /** A file with its keys put back, read for the tunnel. Null when it isn't complete. */
    data class Tunnel(
        val privateKey: String,
        val addresses: List<String>,
        val dns: List<String>,
        val searchDomains: List<String>,
        val mtu: Int?,
        val listenPort: Int?,
        val peers: List<Peer>,
    ) {
        override fun toString() = "Tunnel(${peers.size} peers)"
    }

    fun tunnel(text: String): Tunnel? {
        val sections = parse(text)
        val i = sections.singleOrNull { it.name == "interface" } ?: return null
        val privateKey = i.one("privatekey")?.takeIf(::isKey) ?: return null
        val dnsWords = i.all("dns")
        val peers = sections.filter { it.name == "peer" }.map { p ->
            val e = p.one("endpoint")?.let(::endpointOf)
            Peer(
                publicKey = p.one("publickey")?.takeIf(::isKey) ?: return null,
                presharedKey = p.one("presharedkey")?.takeIf(::isKey),
                host = e?.first,
                port = e?.second,
                allowedIps = p.all("allowedips"),
                keepalive = p.one("persistentkeepalive")?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
        if (peers.isEmpty()) return null
        return Tunnel(
            privateKey = privateKey,
            addresses = i.all("address"),
            dns = dnsWords.filter(::isAddress),
            searchDomains = dnsWords.filterNot(::isAddress),
            mtu = i.one("mtu")?.toIntOrNull(),
            listenPort = i.one("listenport")?.toIntOrNull(),
            peers = peers,
        )
    }

    /** A Base64 key as the lowercase hex wireguard-go's settings take. */
    fun hex(key: String): String = Base64.getDecoder().decode(key).joinToString("") { "%02x".format(it) }

    /** The first server's address and port, or null. */
    fun endpoint(text: String): Pair<String, Int>? =
        parse(text).filter { it.name == "peer" }.firstNotNullOfOrNull { p -> p.one("endpoint")?.let(::endpointOf) }

    /** The file with the first server's Endpoint changed, for Advanced › Server and port. */
    fun withEndpoint(text: String, host: String, port: Int): String {
        var done = false
        var section = ""
        val formatted = if (':' in host) "[$host]:$port" else "$host:$port"
        return text.lines().joinToString("\n") { line ->
            val c = content(line)
            if (c.startsWith("[") && c.endsWith("]")) section = c.substring(1, c.length - 1).trim().lowercase()
            if (!done && section == "peer" && keyOf(line) == "endpoint") {
                done = true
                "Endpoint = $formatted"
            } else {
                line
            }
        }
    }

    /** For showing the file: key values hidden, in case a file still holds them. */
    fun hideSecrets(text: String): String =
        text.lines().joinToString("\n") { line -> keyOf(line)?.takeIf { it in SECRET_KEYS }?.let { "${line.substringBefore('=').trimEnd()} = (hidden)" } ?: line }

    // ---- Reading -------------------------------------------------------------------

    private fun parse(text: String): List<Section> {
        val sections = mutableListOf<Section>()
        for (line in text.lines()) {
            val c = content(line)
            if (c.isEmpty()) continue
            if (c.startsWith("[") && c.endsWith("]")) {
                sections += Section(c.substring(1, c.length - 1).trim().lowercase())
                continue
            }
            val key = keyOf(line) ?: continue
            sections.lastOrNull()?.values?.add(key to valueOf(line))
        }
        return sections
    }

    /** The line without its comment and the spaces around it. */
    private fun content(line: String): String = line.substringBefore('#').trim()

    private fun keyOf(line: String): String? {
        val c = content(line)
        val at = c.indexOf('=')
        return if (at > 0) c.substring(0, at).trim().lowercase() else null
    }

    private fun valueOf(line: String): String = content(line).substringAfter('=').trim()

    private fun label(key: String): String = when (key) {
        "preup" -> "PreUp"; "postup" -> "PostUp"; "predown" -> "PreDown"; "postdown" -> "PostDown"
        "saveconfig" -> "SaveConfig"; "fwmark" -> "FwMark"; "table" -> "Table"
        else -> key
    }

    /** A WireGuard key: 32 bytes in Base64. */
    fun isKey(text: String): Boolean =
        text.length == 44 && runCatching { Base64.getDecoder().decode(text).size == 32 }.getOrDefault(false)

    private fun endpointOf(text: String): Pair<String, Int>? {
        val e = text.trim()
        val (host, port) = if (e.startsWith("[")) {
            val close = e.indexOf(']')
            if (close < 0 || e.getOrNull(close + 1) != ':') return null
            e.substring(1, close) to e.substring(close + 2)
        } else {
            val at = e.lastIndexOf(':')
            if (at <= 0 || e.count { it == ':' } > 1) return null
            e.substring(0, at) to e.substring(at + 1)
        }
        val n = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        return host to n
    }

    private fun isAddress(text: String): Boolean = isV4(text) || isV6(text)

    private fun isNetwork(text: String): Boolean {
        val address = text.substringBefore('/')
        val prefix = text.substringAfter('/', "").ifEmpty { null }?.toIntOrNull()
        if ('/' in text && prefix == null) return false
        return when {
            isV4(address) -> prefix == null || prefix in 0..32
            isV6(address) -> prefix == null || prefix in 0..128
            else -> false
        }
    }

    private fun isV4(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) && p.toInt() <= 255 }
    }

    private fun isV6(text: String): Boolean =
        ':' in text && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' } && text.count { it == ':' } in 2..7
}
