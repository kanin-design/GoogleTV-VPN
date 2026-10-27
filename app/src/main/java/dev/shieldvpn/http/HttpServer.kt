package dev.shieldvpn.http

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The app's one web server, for the phone pages and the paired phones' panel. It listens while
 * something holds it (a screen showing a phone code, the app open with phones paired, the VPN on
 * with phone access), and only on the home network's address, never the tunnel's.
 *
 * It reads each request's line and headers within limits, refuses a name in the Host header,
 * and hands the request to the first [Route] that takes its path.
 */
object HttpServer {
    /** "192.168.1.20:8080" while the server listens, or null. */
    var address by mutableStateOf<String?>(null)
        private set

    private var socket: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private var boundIp: String? = null
    private val holders = LinkedHashSet<String>()
    private val routes = CopyOnWriteArrayList<Route>()

    // Open connections by address: one phone or script can't take every thread.
    private val open = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val main = Handler(Looper.getMainLooper())

    /** What answers a part of the site. */
    interface Route {
        fun takes(path: String): Boolean
        fun handle(r: Request)

        /** The server stopped: requests waiting on an answer should get one now. */
        fun stopped() {}
    }

    /** The newest route for a path comes first, so screens made again take over from the old ones. */
    fun add(route: Route) {
        if (route !in routes) routes.add(0, route)
    }

    fun remove(route: Route) {
        routes -= route
    }

    /** [who] needs the server: it listens until every holder has let go. Main thread only. */
    fun hold(who: String) {
        holders += who
        start()
    }

    fun release(who: String) {
        if (!holders.remove(who)) return
        if (holders.isEmpty()) stop()
    }

    /** Moves to the new address after the TV changed networks, or starts once there is one. Main thread only. */
    fun recheck() {
        if (holders.isEmpty()) return
        if (lanAddress() == boundIp && socket != null) return
        stop()
        start()
    }

    private fun start() {
        if (socket != null || holders.isEmpty()) return
        val ip = lanAddress() ?: return
        // Only on the home network's address: not on the VPN tunnel, where the VPN's side could reach it.
        val server = listOf(8080, 8081, 8090, 0).firstNotNullOfOrNull { port ->
            val s = ServerSocket()
            runCatching {
                s.reuseAddress = true
                s.bind(InetSocketAddress(ip, port))
                s
            }.getOrElse {
                runCatching { s.close() }
                null
            }
        } ?: return
        // Browsers open spare connections and send nothing on them, and every open page waits on one
        // request; enough threads for a few phones, and a short wait for the first line, keep those
        // from holding up the request that matters.
        val threads = ThreadPoolExecutor(2, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue())
        socket = server
        pool = threads
        boundIp = ip
        address = "$ip:${server.localPort}"
        Thread({
            while (true) {
                val s = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                val from = s.inetAddress?.hostAddress.orEmpty()
                val count = open.getOrPut(from) { java.util.concurrent.atomic.AtomicInteger() }
                if (count.incrementAndGet() > MAX_PER_ADDRESS) {
                    count.decrementAndGet()
                    runCatching { s.close() }
                    continue
                }
                try {
                    threads.execute {
                        try {
                            runCatching { handle(s) }.onFailure { Log.w(TAG, "request failed: ${it.message}") }
                        } finally {
                            count.decrementAndGet()
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    count.decrementAndGet()
                    runCatching { s.close() }
                }
            }
        }, "http-server").start()
    }

    private fun stop() {
        runCatching { socket?.close() }
        socket = null
        // Requests under way finish; the routes wake the ones that wait.
        pool?.shutdown()
        pool = null
        boundIp = null
        address = null
        routes.forEach { it.stopped() }
    }

    val running: Boolean get() = socket != null

    // ---- Requests ------------------------------------------------------------------

    private fun handle(client: Socket): Unit = client.use { s ->
        s.soTimeout = 3_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        // The line and the headers come in time, however slowly the bytes trickle.
        val deadline = System.currentTimeMillis() + HEAD_MS
        val requestLine = readLine(input, deadline) ?: return
        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, deadline) ?: return
            if (line.isEmpty()) break
            headerBytes += line.length
            // Before anything is checked, a request gets only so much room.
            if (headers.size >= MAX_HEADERS || headerBytes > MAX_HEADER_BYTES) return Request.send(out, 400, "text/plain; charset=utf-8", "Too many headers.".toByteArray())
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val target = parts[1]
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: -1 } ?: 0
        val r = Request(
            method = parts[0], path = target.substringBefore('?'), query = parseQuery(target.substringAfter('?', "")),
            headers = headers, from = s.inetAddress?.hostAddress.orEmpty(), length = length, input = input, out = out, socket = s,
        )
        if (length < 0) return r.text(400, "A length is needed.")
        // The page is reached by the TV's address. A name in the Host header is another site
        // pointing its name at this TV (DNS rebinding), which is refused.
        val host = headers["host"]?.substringBeforeLast(':')
        if (host == null || !IPV4.matches(host)) {
            r.skipBody()
            return r.text(403, "Open the address shown on the TV.")
        }
        val route = routes.firstOrNull { it.takes(r.path) }
        when {
            route != null -> route.handle(r)
            // Without the screens there are no codes; the panel is what a paired phone uses.
            r.path == "/" -> r.send(302, "text/plain; charset=utf-8", ByteArray(0), "Location" to "/panel/")
            r.path.startsWith("/api/") -> {
                r.skipBody()
                // A per-screen page left open while the screens are gone: what a stopped server would say.
                r.json(410, JSONObject().put("error", "ended").put("text", "This code has ended. Open the screen on the TV again."))
            }
            else -> {
                r.skipBody()
                r.text(404, "Not found.", r.method == "HEAD")
            }
        }
    }

    private const val TAG = "HttpServer"
    private const val MAX_THREADS = 24
    private const val MAX_HEADERS = 64
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_PER_ADDRESS = 10
    private const val HEAD_MS = 5_000L
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

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
            runCatching { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                .getOrDefault("" to "")
        }

    private fun readLine(input: InputStream, deadline: Long): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            if (System.currentTimeMillis() > deadline) return null
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            if (buf.size() > 8192) return null
            buf.write(b)
        }
    }
}

/** One request, its line and headers read; the body is read by the route that wants it. */
class Request internal constructor(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    /** The phone's address, for counting its wrong tries. */
    val from: String,
    val length: Int,
    private val input: InputStream,
    private val out: OutputStream,
    private val socket: Socket,
) {
    val head: Boolean get() = method == "HEAD"

    /** Only the pages send this header; a form on another site can't, without a preflight this server never answers. */
    val fromPage: Boolean get() = headers["x-phone"] == "1"

    /** How long to wait for the rest of the request, and a long wait's answer. */
    fun waitUpTo(ms: Int) {
        socket.soTimeout = ms
    }

    /** The body, when it is no longer than [max] and comes within 20 seconds; else null. */
    fun body(max: Int): ByteArray? {
        if (length > max) {
            skipBody()
            return null
        }
        val body = ByteArray(length)
        var read = 0
        val deadline = System.currentTimeMillis() + BODY_MS
        while (read < length) {
            if (System.currentTimeMillis() > deadline) return null
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return body
    }

    /** A phone still sending sees a reset instead of the answer if the body is left unread. */
    fun skipBody() {
        if (length !in 1..MAX_DRAIN) return
        val buf = ByteArray(16 * 1024)
        var left = length
        while (left > 0) {
            val n = runCatching { input.read(buf, 0, minOf(buf.size, left)) }.getOrDefault(-1)
            if (n < 0) return
            left -= n
        }
    }

    fun json(status: Int, body: JSONObject) = send(status, "application/json; charset=utf-8", body.toString().toByteArray())

    fun text(status: Int, body: String, head: Boolean = false) = send(status, "text/plain; charset=utf-8", body.toByteArray(), head = head)

    fun send(status: Int, type: String, body: ByteArray, vararg extra: Pair<String, String>, head: Boolean = false) = send(out, status, type, body, *extra, head = head)

    companion object {
        /** Up to this much of a refused upload is read and dropped, so the phone gets the answer. */
        private const val MAX_DRAIN = 8 * 1024 * 1024
        private const val BODY_MS = 20_000L

        internal fun send(out: OutputStream, status: Int, type: String, body: ByteArray, vararg extra: Pair<String, String>, head: Boolean = false) {
            val reason = when (status) {
                200 -> "OK"
                302 -> "Found"
                304 -> "Not Modified"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                410 -> "Gone"
                413 -> "Payload Too Large"
                429 -> "Too Many Requests"
                else -> "Error"
            }
            val header = buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: $type\r\n")
                append("Content-Length: ${body.size}\r\n")
                if (extra.none { it.first == "Cache-Control" }) append("Cache-Control: no-store\r\n")
                append("X-Content-Type-Options: nosniff\r\n")
                append("Referrer-Policy: no-referrer\r\n")
                append("X-Frame-Options: DENY\r\n")
                append("Connection: close\r\n")
                extra.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            try {
                out.write(header.toByteArray())
                if (!head) out.write(body)
                out.flush()
            } catch (_: IOException) {
                // The phone went away mid-answer; nothing to tell it.
            }
        }
    }
}
