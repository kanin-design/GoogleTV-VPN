package dev.shieldvpn.vpn

import java.net.HttpURLConnection
import java.net.URL

/**
 * One-off HTTPS requests. Each one closes its connection when it is done: Android keeps idle
 * connections and a thread per connection otherwise, and every tunnel restart opens new ones.
 */
object Http {

    class Response(val code: Int, val body: ByteArray?)

    /** GETs [url]. The body is read only for a 200, up to [limit] bytes. */
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 6000, limit: Int = 512 * 1024): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val body = if (code == 200) c.inputStream.use { readLimited(it, limit) } else null
            return Response(code, body)
        } finally {
            c.disconnect()
        }
    }

    /** The address the internet sees, from [url], or null. */
    fun publicIp(url: String): String? = runCatching {
        get(url).body?.let { String(it).trim() }
            ?.takeIf { it.length in 3..45 && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == ':' } }
    }.getOrNull()

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) break
        }
        return out.toByteArray()
    }
}
