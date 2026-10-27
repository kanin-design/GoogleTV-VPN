package dev.shieldvpn.control

import dev.shieldvpn.vpn.ConfigInfo
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.Login
import dev.shieldvpn.vpn.Protocol
import dev.shieldvpn.vpn.VpnKind

/**
 * The words for things that show in more than one place: a profile's status and sign-in, a
 * server's address, the backup line. The TV's screens and the phone pages take them from here, so
 * one state is never said two ways. Plain Kotlin, tested on the JVM (WordsTest).
 */
object Words {

    // ---- Profiles ---------------------------------------------------------------------

    /** The name's help: a row's subtitle, and the same as a sentence for a sheet or the phone. */
    const val NAME_HELP = "Shown on cards and on Connect"
    const val NAME_HELP_PHONE = "Shown on the TV’s cards and on Connect."

    const val DUPLICATE_HELP = "A copy to try other settings"

    fun deleteHelp(hasPassword: Boolean) = if (hasPassword) "Removes it and its saved password" else "Removes it from this device"

    /**
     * The red bar's question. When [name] is the startup profile, [startupGoesTo] is the profile
     * that gets the slot back, or null when startup turns off.
     */
    fun deleteQuestion(name: String, hasPassword: Boolean, isStartup: Boolean = false, startupGoesTo: String? = null, connected: Boolean = false): String {
        val q = (if (hasPassword) "Delete $name and its saved password?" else "Delete $name?") + if (connected) " It disconnects first." else ""
        if (!isStartup) return q
        return q + if (startupGoesTo != null) " $startupGoesTo connects at startup instead." else " Nothing connects at startup then."
    }

    fun notSaved(name: String) = "$name wasn’t saved"

    /** "The .ovpn file" or "The .conf file". */
    fun fileTitle(kind: VpnKind?) = if (kind == VpnKind.WireGuard) "The .conf file" else "The .ovpn file"

    /** "OPENVPN" or "WIREGUARD", above a profile's name and as a card's tag. */
    fun kind(kind: VpnKind) = when (kind) {
        VpnKind.OpenVpn -> "OpenVPN"
        VpnKind.WireGuard -> "WireGuard"
    }

    // ---- Sign-in ------------------------------------------------------------------------

    /** How a profile signs in: [summary] is a row's line, [method] its value chip. */
    data class SignIn(val summary: String, val method: String)

    fun signIn(info: ConfigInfo?, login: Login?, keyAlias: String?): SignIn {
        val saved = login != null && login.hasPassword && login.username.isNotEmpty()
        val needsLogin = info?.needsLogin != false
        return when {
            info?.kind == VpnKind.WireGuard -> SignIn("Key in the profile", "Key")
            keyAlias != null && needsLogin -> SignIn(if (saved) "Keystore key, password saved for ${login!!.username}" else "Keystore key, asks for a password", "Keystore + password")
            keyAlias != null -> SignIn("Key in the device’s keystore", "Keystore")
            !needsLogin && info?.clientCert == true -> SignIn("Certificate in the file", "Certificate")
            !needsLogin -> SignIn("No certificate or password in the file", "None")
            saved -> SignIn("Password saved for ${login!!.username}", "Password")
            else -> SignIn("Asks for a password", "Password")
        }
    }

    // ---- Servers ------------------------------------------------------------------------

    /** "vpn.example.net:1194", or "[2001:db8::1]:1194". */
    fun address(host: String, port: Int?): String {
        val h = if (':' in host) "[$host]" else host
        return if (port == null) h else "$h:$port"
    }

    /** "vpn.example.net:1194 · UDP · AES-128-CBC": the same on Connect, in reviews and on the phone. */
    fun serverLine(host: String, port: Int?, protocol: Protocol?, cipher: String? = null): String =
        listOfNotNull(address(host, port).takeIf { host.isNotEmpty() }, protocol?.label, cipher).joinToString(" · ")

    /** "a", "a and b", "a, b and c". */
    fun and(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    // ---- Time ---------------------------------------------------------------------------

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - millis) / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours h ago"
            days == 1L -> "yesterday"
            days < 7 -> "$days days ago"
            days < 14 -> "last week"
            else -> "${days / 7} weeks ago"
        }
    }

    fun lastConnected(lastUsed: Long?, now: Long = System.currentTimeMillis()) =
        lastUsed?.let { "Last connected ${ago(it, now)}" } ?: "Never connected"

    // ---- Backup -------------------------------------------------------------------------

    fun lastBackup(last: Pair<Long, Int>?, now: Long = System.currentTimeMillis()): String =
        last?.let { (at, n) -> "Last backup ${ago(at, now)}, with $n profile${if (n == 1) "" else "s"}" } ?: "Not backed up yet"
}

/** How a profile is doing, in one word and a detail, the same on every card, list, Connect and the phone. */
data class ProfileStatus(val word: String, val detail: String?, val tone: Tone) {
    enum class Tone { On, Busy, Off, Bad }

    /** One line for a card or a list row: the detail when idle, else the word with any reason. */
    val line: String
        get() = when {
            tone == Tone.Off -> detail ?: word
            // The first sentence: the rest (what to check) is said where there is room for it.
            tone == Tone.Bad && detail != null -> "$word: ${detail.substringBefore(". ").trimEnd('.')}"
            else -> word
        }

    companion object {
        /**
         * [live] is the tunnel's state when it belongs to this profile, and null otherwise; a
         * failure counts only for the profile that failed.
         */
        fun of(c: Connection, live: Live?, asksForLogin: Boolean, now: Long = System.currentTimeMillis()): ProfileStatus = when (live) {
            Live.Connected -> ProfileStatus("Connected", null, Tone.On)
            Live.Connecting -> ProfileStatus("Connecting…", null, Tone.Busy)
            Live.Paused -> ProfileStatus("Paused", null, Tone.Busy)
            Live.Blocked -> ProfileStatus("Blocking traffic", null, Tone.Bad)
            is Live.Failed -> ProfileStatus("Failed", live.reason, Tone.Bad)
            null -> when {
                asksForLogin -> ProfileStatus("Not connected", "Asks for a password", Tone.Off)
                c.lastError != null -> ProfileStatus("Failed", c.lastError, Tone.Bad)
                else -> ProfileStatus("Not connected", Words.lastConnected(c.lastUsed, now), Tone.Off)
            }
        }
    }

    sealed interface Live {
        data object Connected : Live
        data object Connecting : Live
        data object Paused : Live
        data object Blocked : Live
        data class Failed(val reason: String) : Live
    }
}
