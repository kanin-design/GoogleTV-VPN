package dev.shieldvpn.control

import dev.shieldvpn.vpn.Http
import dev.shieldvpn.vpn.TunnelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings › Diagnostics' connection test, and the test after a phone's Apply and test: the
 * tunnel is up, DNS answers, and traffic reaches the internet.
 */
object ConnectionTest {
    enum class Result { Ok, Warn, Fail }

    data class Check(val title: String, val result: Result, val detail: String) {
        val ok: Boolean get() = result == Result.Ok

        /** "OK: A lookup took 41 ms", as the phone pages list them. */
        val line: String get() = "${if (result == Result.Ok) "OK" else if (result == Result.Warn) "Check" else "Failed"}: $detail"
    }

    const val TUNNEL = "Tunnel is up"
    const val DNS = "DNS answers"
    const val INTERNET = "Traffic reaches the internet"

    fun tunnel(connected: Boolean, t: TunnelInfo?): Check =
        if (connected) Check(TUNNEL, Result.Ok, t?.address?.let { "Tunnel IP $it" } ?: "Connected") else Check(TUNNEL, Result.Fail, "Not connected")

    /** It shows a lookup works and which server the tunnel set; it doesn't trace the path. */
    suspend fun dns(t: TunnelInfo?): Check {
        val ms = withContext(Dispatchers.IO) {
            runCatching {
                val start = System.nanoTime()
                java.net.InetAddress.getByName("example.com")
                (System.nanoTime() - start) / 1_000_000
            }.getOrNull()
        }
        val server = t?.dns?.firstOrNull()?.substringBefore(' ')
        return if (ms != null && server != null) {
            Check(DNS, Result.Ok, "A lookup took $ms ms · the tunnel’s DNS is $server")
        } else {
            Check(DNS, Result.Fail, if (ms != null) "A lookup took $ms ms · the tunnel’s DNS is not set" else "No answer from DNS")
        }
    }

    suspend fun internet(): Check {
        val answer = withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            Http.publicIp("https://api.ipify.org")?.let { ip -> ip to (System.nanoTime() - start) / 1_000_000 }
        }
        return answer?.let { (ip, ms) -> Check(INTERNET, Result.Ok, "Sites see $ip · $ms ms") } ?: Check(INTERNET, Result.Fail, "No answer through the tunnel")
    }
}
