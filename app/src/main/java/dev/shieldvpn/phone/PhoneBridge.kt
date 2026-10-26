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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A small web server on the TV for moving things between the TV and a phone: the phone
 * scans a QR code, opens a page, and sends a file or text, or downloads one.
 *
 * It runs only while a screen that needs it is open, answers only on the home network, and
 * every request needs the six-digit code shown on the TV. A phone that types five wrong codes
 * waits a minute before it can try again; other phones are not affected.
 */
class PhoneBridge private constructor(
    private val server: ServerSocket,
    val code: String,
    val address: String,
    page: Page,
    private val onReceive: (Received) -> String?,
    private val onDownloaded: () -> Unit,
) {
    /** What the page shows. It can change during a session, for example to the next field. */
    @Volatile var page: Page = page

    /** What the phone's page offers. */
    sealed interface Page {
        val title: String
        val lede: String

        /**
         * Pick a file or paste its text, and send it to the TV. With [login], a profile that asks
         * for a username and password gets a next step for them, and they go with the file.
         */
        data class Upload(override val title: String, override val lede: String, val accept: String, val paste: Boolean, val login: Boolean = false) : Page

        /** Type into a field on the TV. */
        data class Type(override val title: String, override val lede: String, val label: String, val secret: Boolean) : Page

        /** Download a file from the TV. */
        data class Download(override val title: String, override val lede: String, val fileName: String, val mime: String, val body: ByteArray) : Page
    }

    /** What the phone sent: a file's name and bytes, or the text typed or pasted, and a login typed with a profile. */
    data class Received(val name: String, val bytes: ByteArray, val username: String = "", val password: String = "")

    /** The address to open on the phone, with the code in it. */
    val url: String get() = "http://$address/?c=$code"

    // Browsers open spare connections and send nothing on them; enough threads, and a short wait
    // for the first line, keep those from holding up the request that matters.
    private val pool = Executors.newFixedThreadPool(8)
    private val wrong = ConcurrentHashMap<String, Tries>()
    @Volatile private var open = true

    /** Wrong codes from one phone, and until when it must wait. */
    private class Tries(var count: Int = 0, var until: Long = 0L)

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
        s.soTimeout = 3_000
        val from = s.inetAddress?.hostAddress.orEmpty()
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
        s.soTimeout = 15_000
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))

        val page = page
        val head = method == "HEAD"
        when {
            (method == "GET" || head) && path == "/" -> {
                // The file's name and size only for someone with the code.
                val given = query["c"].orEmpty()
                send(out, 200, "text/html; charset=utf-8", PhonePage.html(page, given, given.filter { it.isDigit() } == code).toByteArray(), head = head)
            }
            (method == "GET" || head) && path == "/file" -> {
                val p = page as? Page.Download ?: return send(out, 404, "text/plain", "Nothing to download here.".toByteArray(), head = head)
                tooMany(from)?.let { return send(out, 429, "text/plain", it.toByteArray(), head = head) }
                if (!checkCode(query["c"], from)) return send(out, 403, "text/plain", "Wrong code. Look at the TV and try again.".toByteArray(), head = head)
                send(out, 200, p.mime, p.body, "Content-Disposition" to disposition(p.fileName), head = head)
                if (!head) onDownloaded()
            }
            method == "POST" && path == "/send" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: return send(out, 411, "text/plain", "Length required".toByteArray())
                // A phone still sending sees a reset instead of the answer if the body is left unread.
                val refuse = { status: Int, why: String ->
                    if (length <= MAX_DRAIN) drain(input, length)
                    send(out, status, "text/plain", why.toByteArray())
                }
                if (page is Page.Download) return refuse(404, "This page only downloads.")
                tooMany(from)?.let { return refuse(429, it) }
                if (!checkCode(headers["x-code"], from)) return refuse(403, "Wrong code. Look at the TV and try again.")
                if (length > MAX_BODY) return refuse(413, "That file is too large. A profile or backup is much smaller.")
                val body = readExactly(input, length) ?: return
                val name = URLDecoder.decode(headers["x-name"].orEmpty(), "UTF-8").take(120)
                val username = URLDecoder.decode(headers["x-user"].orEmpty(), "UTF-8").take(256)
                val password = URLDecoder.decode(headers["x-pass"].orEmpty(), "UTF-8").take(256)
                val problem = onReceive(Received(name, body, username, password))
                if (problem == null) send(out, 200, "text/plain", "Sent. Look at the TV.".toByteArray())
                else send(out, 422, "text/plain", problem.toByteArray())
            }
            else -> send(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun checkCode(given: String?, from: String): Boolean {
        val tries = wrong.getOrPut(from) { Tries() }
        if (given?.filter { it.isDigit() } == code) {
            tries.count = 0
            return true
        }
        if (++tries.count >= 5) {
            tries.count = 0
            tries.until = System.currentTimeMillis() + 60_000
        }
        return false
    }

    /** Why this phone has to wait, after too many wrong codes, or null. */
    private fun tooMany(from: String): String? {
        val until = wrong[from]?.until ?: return null
        return if (System.currentTimeMillis() < until) "Too many wrong codes. Wait a minute, then try again with the code on the TV." else null
    }

    /** The download's name, kept with its letters for phones that read it, and plain for those that don't. */
    private fun disposition(name: String): String {
        val plain = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$plain\"; filename*=UTF-8''$encoded"
    }

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, vararg extra: Pair<String, String>, head: Boolean = false) {
        val reason = when (status) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "Unprocessable"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(header.toByteArray())
        if (!head) out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "PhoneBridge"
        private const val MAX_BODY = 512 * 1024

        /** Up to this much of a refused upload is read and dropped, so the phone gets the answer. */
        private const val MAX_DRAIN = 8 * 1024 * 1024

        /**
         * Starts a session showing [page]. [onReceive] gets what the phone sends and returns
         * null when it was accepted, or what is wrong; [onDownloaded] runs when a phone has
         * downloaded the page's file. Returns null without a home network.
         */
        fun start(page: Page, onDownloaded: () -> Unit = {}, onReceive: (Received) -> String? = { null }): PhoneBridge? {
            val ip = lanAddress() ?: return null
            // Only on the home network's address: not on the VPN tunnel, where the VPN's side could reach it.
            val server = listOf(8080, 8081, 8090, 0).firstNotNullOfOrNull { port ->
                runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(ip, port)) } }.getOrNull()
            } ?: return null
            val code = (SecureRandom().nextInt(900_000) + 100_000).toString()
            return PhoneBridge(server, code, "$ip:${server.localPort}", page, onReceive, onDownloaded).also { it.serve() }
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

        private fun drain(input: InputStream, length: Int) {
            val buf = ByteArray(16 * 1024)
            var left = length
            while (left > 0) {
                val n = runCatching { input.read(buf, 0, minOf(buf.size, left)) }.getOrDefault(-1)
                if (n < 0) return
                left -= n
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
