package dev.shieldvpn.phone

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small web server on the TV for moving things between the TV and a phone: the phone
 * scans a QR code, opens a page, and sends a file or text, or downloads one.
 *
 * It runs only while a screen that needs it is open, answers only on the home network, and
 * every request needs the six-digit code shown on the TV. Five wrong codes end the session.
 */
class PhoneBridge private constructor(
    private val server: ServerSocket,
    val code: String,
    val address: String,
    page: Page,
    private val onReceive: (Received) -> String?,
) {
    /** What the page shows. It can change during a session, for example to the next field. */
    @Volatile var page: Page = page

    /** What the phone's page offers. */
    sealed interface Page {
        val title: String
        val lede: String

        /** Pick a file or paste its text, and send it to the TV. */
        data class Upload(override val title: String, override val lede: String, val accept: String, val paste: Boolean) : Page

        /** Type into a field on the TV. */
        data class Type(override val title: String, override val lede: String, val label: String, val secret: Boolean) : Page

        /** Download a file from the TV. */
        data class Download(override val title: String, override val lede: String, val fileName: String, val mime: String, val body: ByteArray) : Page
    }

    /** What the phone sent: a file's name and bytes, or the text typed or pasted. */
    data class Received(val name: String, val bytes: ByteArray)

    /** The address to open on the phone, with the code in it. */
    val url: String get() = "http://$address/?c=$code"

    private val pool = Executors.newFixedThreadPool(3)
    private val wrong = AtomicInteger(0)
    @Volatile private var open = true

    private fun serve() {
        Thread({
            while (open) {
                val socket = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                pool.execute { runCatching { handle(socket) }.onFailure { Log.w(TAG, "request failed: ${it.message}") } }
            }
        }, "phone-bridge").start()
    }

    fun stop() {
        open = false
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) = socket.use { s ->
        s.soTimeout = 15_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))

        val page = page
        when {
            method == "GET" && path == "/" -> send(out, 200, "text/html; charset=utf-8", PhonePage.html(page, query["c"].orEmpty()).toByteArray())
            method == "GET" && path == "/file" -> {
                val p = page as? Page.Download ?: return send(out, 404, "text/plain", "Nothing to download".toByteArray())
                if (!checkCode(query["c"])) return send(out, 403, "text/plain", "Wrong code. Look at the TV and try again.".toByteArray())
                send(out, 200, p.mime, p.body, "Content-Disposition" to "attachment; filename=\"${p.fileName}\"")
            }
            method == "POST" && path == "/send" -> {
                if (page is Page.Download) return send(out, 404, "text/plain", "This page only downloads".toByteArray())
                if (!checkCode(headers["x-code"])) return send(out, 403, "text/plain", "Wrong code. Look at the TV and try again.".toByteArray())
                val length = headers["content-length"]?.toIntOrNull() ?: return send(out, 411, "text/plain", "Length required".toByteArray())
                if (length > MAX_BODY) return send(out, 413, "text/plain", "That file is too large for a profile.".toByteArray())
                val body = readExactly(input, length) ?: return
                val name = URLDecoder.decode(headers["x-name"].orEmpty(), "UTF-8").take(120)
                val problem = onReceive(Received(name, body))
                if (problem == null) send(out, 200, "text/plain", "Sent. Look at the TV.".toByteArray())
                else send(out, 422, "text/plain", problem.toByteArray())
            }
            else -> send(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun checkCode(given: String?): Boolean {
        if (given?.filter { it.isDigit() } == code) return true
        if (wrong.incrementAndGet() >= 5) stop()
        return false
    }

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, vararg extra: Pair<String, String>) {
        val reason = when (status) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            else -> "Unprocessable"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "PhoneBridge"
        private const val MAX_BODY = 512 * 1024

        /**
         * Starts a session showing [page]. [onReceive] gets what the phone sends and returns
         * null when it was accepted, or what is wrong. Returns null without a home network.
         */
        fun start(page: Page, onReceive: (Received) -> String? = { null }): PhoneBridge? {
            val ip = lanAddress() ?: return null
            // Only on the home network's address: not on the VPN tunnel, where the VPN's side could reach it.
            val server = listOf(8080, 8081, 8090, 0).firstNotNullOfOrNull { port ->
                runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(ip, port)) } }.getOrNull()
            } ?: return null
            val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
            return PhoneBridge(server, code, "$ip:${server.localPort}", page, onReceive).also { it.serve() }
        }

        /** The TV's address on the home network: Wi-Fi or Ethernet, never the tunnel. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        private fun parseQuery(q: String): Map<String, String> =
            q.split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                if (buf.size() > 8192) return null
                buf.write(b)
            }
        }

        private fun readExactly(input: InputStream, length: Int): ByteArray? {
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            return body
        }
    }
}
