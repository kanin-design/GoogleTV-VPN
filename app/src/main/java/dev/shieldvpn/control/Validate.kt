package dev.shieldvpn.control

/**
 * The checks on what a person types, wherever it is typed: a TV sheet, a phone page or the
 * panel. Each answers with the sentence shown under the field, or null when it is fine.
 */
object Validate {

    /** The DNS servers or domains typed, split at spaces, commas and lines. */
    fun dnsServers(text: String): List<String> = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

    fun dnsProblem(text: String): String? = dnsServers(text).firstOrNull { !isIpAddress(it) }?.let { "$it is not an IP address" }

    fun dnsProblem(servers: List<String>): String? = servers.firstOrNull { !isIpAddress(it) }?.let { "$it is not an IP address" }

    /** An IPv4 or IPv6 address written out, in ASCII digits: what Android takes as a DNS server. */
    fun isIpAddress(s: String): Boolean {
        val v4 = s.split('.')
        if (v4.size == 4) return v4.all { it.length in 1..3 && it.all { c -> c in '0'..'9' } && it.toInt() <= 255 }
        val hex = s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
        val groups = s.split(':')
        return hex && s.count { it == ':' } in 2..7 && s.split("::").size <= 2 && groups.all { it.length <= 4 } &&
            (s.contains("::") || groups.size == 8)
    }

    fun hostProblem(host: String): String? = if (host.isEmpty() || host.any { it.isWhitespace() }) "Type the server’s address" else null

    fun portProblem(port: String): String? = if (port.trim().toIntOrNull()?.let { it in 1..65535 } != true) "A number from 1 to 65535" else null

    fun nameProblem(name: String): String? = if (name.trim().isEmpty()) "A profile needs a name" else null

    fun usernameProblem(user: String): String? = if (user.isBlank()) "Type the username" else null

    fun passwordProblem(pw: String): String? = if (pw.isEmpty()) "Type the password" else null

    /** A new passphrase typed twice: which of the two is wrong, and why. */
    fun passphraseProblem(first: String, again: String): Pair<Int, String>? = when {
        first.length < 6 -> 0 to "At least 6 characters"
        first != again -> 1 to "Not the same as the first. Type it again"
        else -> null
    }

    fun pinProblem(pin: String): String? = if (pin.length !in 4..8 || !pin.all(Char::isDigit)) "The PIN has 4 to 8 digits" else null
}

/**
 * Wrong PINs in a row, typed on the TV or on a phone, for this run of the app. After five, each
 * try waits: 30 seconds, then twice as long each time, so a 4-digit PIN can't be tried through.
 */
object PinTries {
    @Volatile var misses = 0
    @Volatile var waitUntil = 0L

    /** Where the count is kept, so a restart of the app doesn't give five free tries. Set by [Control]. */
    @Volatile var keep: (Int, Long) -> Unit = { _, _ -> }

    @Synchronized
    fun missed() {
        misses++
        if (misses >= 5) waitUntil = System.currentTimeMillis() + 30_000L * (1L shl (misses - 5).coerceAtMost(6))
        keep(misses, waitUntil)
    }

    fun secondsLeft(): Long = ((waitUntil - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)

    /** Checks [pin] with [check], counting it: null when right, else what to say. */
    @Synchronized
    fun attempt(pin: String, check: (String) -> Boolean): String? {
        val wait = secondsLeft()
        if (wait > 0) return "Too many wrong PINs. Try again in $wait s."
        if (check(pin)) {
            if (misses != 0) keep(0, 0)
            misses = 0
            return null
        }
        missed()
        return if (secondsLeft() > 0) "Wrong PIN. Wait ${secondsLeft()} s before the next try." else "That isn’t the PIN"
    }
}
