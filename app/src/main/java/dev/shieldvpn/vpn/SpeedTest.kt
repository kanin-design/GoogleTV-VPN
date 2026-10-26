package dev.shieldvpn.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Measures the connection against Cloudflare's speed test (speed.cloudflare.com): the round trip
 * of a TCP handshake, then download and upload over a few connections at once. The app's own
 * traffic goes through the tunnel like every other app's, so while connected this measures the VPN.
 * It runs only when the user starts it, and each phase stops after a few seconds.
 */
object SpeedTest {

    enum class Phase { Ping, Down, Up }

    data class Result(val pingMs: Int, val downMbps: Double, val upMbps: Double, val at: Long)

    /** The key a test without the VPN is kept under, beside the profiles' ids. */
    const val DIRECT = "direct"

    private const val HOST = "speed.cloudflare.com"
    private const val STREAMS = 4
    private const val DOWN_SECONDS = 8.0
    private const val UP_SECONDS = 8.0
    /** The first part of each phase is left out of the result, while TCP is still speeding up. */
    private const val WARMUP_SECONDS = 1.5
    private const val PHASE_BYTE_CAP = 300L * 1024 * 1024
    private const val CHUNK = 64 * 1024

    /**
     * Runs the three phases in turn. [report] is called on the caller's thread with the phase and
     * its live value: milliseconds for [Phase.Ping], megabits a second for the others.
     */
    suspend fun run(report: (Phase, Double) -> Unit): Result {
        report(Phase.Ping, 0.0)
        val ping = ping()
        report(Phase.Ping, ping.toDouble())
        val down = transfer(upload = false, DOWN_SECONDS) { report(Phase.Down, it) }
        if (down <= 0.0) throw IOException("Cloudflare's speed test sent nothing back")
        val up = transfer(upload = true, UP_SECONDS) { report(Phase.Up, it) }
        return Result(ping, down, up, System.currentTimeMillis())
    }

    /** The median of five TCP handshakes with the test server. */
    private suspend fun ping(): Int = withContext(Dispatchers.IO) {
        val address = InetAddress.getByName(HOST)
        val times = (1..5).map {
            Socket().use { s ->
                val start = System.nanoTime()
                s.connect(InetSocketAddress(address, 443), 4000)
                ((System.nanoTime() - start) / 1_000_000).toInt()
            }
        }
        times.sorted()[times.size / 2].coerceAtLeast(1)
    }

    /**
     * Moves data for [seconds] over [STREAMS] connections and returns megabits a second. A download
     * counts bytes as they arrive, after a warmup while TCP speeds up. An upload counts a request
     * only once the server has answered it: written bytes may still wait in the TV's send buffer,
     * which holds a megabyte or more, so counting writes shows a burst and then nothing.
     * Closing everything in [open] ends the reads and writes still waiting on the network.
     */
    private suspend fun transfer(upload: Boolean, seconds: Double, live: (Double) -> Unit): Double = coroutineScope {
        val moved = AtomicLong()
        // When the first upload body started, after its connection was set up.
        val begun = AtomicLong()
        val stop = AtomicBoolean(false)
        val open = ConcurrentHashMap.newKeySet<Closeable>()
        repeat(STREAMS) { launch(Dispatchers.IO) { if (upload) uploader(moved, begun, stop, open) else downloader(moved, stop, open) } }
        try {
            val start = System.nanoTime()
            var warmBytes = -1L
            var warmAt = 0L
            var lastBytes = 0L
            var lastAt = start
            var smooth = 0.0
            var result = 0.0
            var done = false
            while (!done) {
                delay(200)
                val now = System.nanoTime()
                val bytes = moved.get()
                val elapsed = (now - start) / 1e9
                val average = if (upload) {
                    begun.get().takeIf { it != 0L && bytes > 0 }?.let { mbps(bytes, now - it) }
                } else {
                    // The last moments, smoothed so the gauge does not jump.
                    smooth = mbps(bytes - lastBytes, now - lastAt).let { if (smooth == 0.0) it else smooth * 0.6 + it * 0.4 }
                    lastBytes = bytes
                    lastAt = now
                    if (warmBytes < 0 && elapsed >= WARMUP_SECONDS) {
                        warmBytes = bytes
                        warmAt = now
                    }
                    if (warmBytes >= 0 && now > warmAt) mbps(bytes - warmBytes, now - warmAt) else null
                }
                live(if (upload) average ?: 0.0 else average?.let { it * 0.5 + smooth * 0.5 } ?: smooth)
                if (elapsed >= seconds || bytes > PHASE_BYTE_CAP) {
                    result = average ?: if (upload) 0.0 else mbps(bytes, now - start)
                    done = true
                }
            }
            result
        } finally {
            stop.set(true)
            open.forEach { runCatching { it.close() } }
        }
    }

    private fun downloader(moved: AtomicLong, stop: AtomicBoolean, open: MutableSet<Closeable>) {
        val buf = ByteArray(CHUNK)
        retrying(stop) {
            val c = URL("https://$HOST/__down?bytes=${PHASE_BYTE_CAP / STREAMS}").openConnection() as HttpURLConnection
            val closer = Closeable { c.disconnect() }
            open.add(closer)
            try {
                if (stop.get()) return@retrying
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.useCaches = false
                c.setRequestProperty("Connection", "close")
                c.inputStream.use { input ->
                    while (!stop.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        moved.addAndGet(n.toLong())
                    }
                }
            } finally {
                open.remove(closer)
                c.disconnect()
            }
        }
    }

    /**
     * Posts zeros to the test server over one kept-alive connection, so a slow line does not pay
     * for a new handshake each time. Requests grow to about two seconds each.
     */
    private fun uploader(moved: AtomicLong, begun: AtomicLong, stop: AtomicBoolean, open: MutableSet<Closeable>) {
        val buf = ByteArray(CHUNK)
        var size = 64L * 1024
        retrying(stop) {
            tlsSocket().use { socket ->
                open.add(socket)
                try {
                    val out = socket.outputStream
                    val input = socket.inputStream.buffered()
                    while (!stop.get()) {
                        val started = System.nanoTime()
                        begun.compareAndSet(0L, started)
                        out.write("POST /__up HTTP/1.1\r\nHost: $HOST\r\nContent-Type: application/octet-stream\r\nContent-Length: $size\r\n\r\n".toByteArray())
                        var sent = 0L
                        while (sent < size && !stop.get()) {
                            val n = minOf(CHUNK.toLong(), size - sent).toInt()
                            out.write(buf, 0, n)
                            sent += n
                        }
                        out.flush()
                        if (stop.get()) break
                        // The answer comes once the whole body has arrived.
                        val keepAlive = readAnswer(input)
                        moved.addAndGet(size)
                        val took = (System.nanoTime() - started) / 1e9
                        size = (size * 2.0 / took.coerceAtLeast(0.05)).toLong().coerceIn(64L * 1024, 32L * 1024 * 1024)
                        if (!keepAlive) break
                    }
                } finally {
                    open.remove(socket)
                }
            }
        }
    }

    private fun tlsSocket(): SSLSocket {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(HOST, 443), 5000)
            // Long enough for a large body to cross a slow line before the answer comes.
            plain.soTimeout = 20_000
            val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, HOST, 443, true) as SSLSocket
            socket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, socket.session)) {
                socket.close()
                throw IOException("The speed test server's certificate is not for $HOST")
            }
            return socket
        } catch (e: IOException) {
            plain.close()
            throw e
        }
    }

    /** Reads one HTTP answer and its body. Returns whether the server keeps the connection open. */
    private fun readAnswer(input: InputStream): Boolean {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("The speed test closed the connection")
            head.append(b.toChar())
            if (head.length > 16 * 1024) throw IOException("The speed test sent a strange answer")
        }
        val lines = head.lines()
        val code = lines.first().split(' ').getOrNull(1)?.toIntOrNull()
        if (code != 200) throw IOException("The speed test answered ${lines.first().trim()}")
        val headers = lines.drop(1).mapNotNull { line ->
            line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim().lowercase() }
        }.toMap()
        var left = headers["content-length"]?.toLongOrNull() ?: 0L
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) break
                left--
            } else {
                left -= skipped
            }
        }
        return headers["connection"] != "close" && headers["transfer-encoding"] == null
    }

    /** Runs [attempt] again until [stop]; a failed one is retried after a moment. */
    private inline fun retrying(stop: AtomicBoolean, attempt: () -> Unit) {
        while (!stop.get()) {
            try {
                attempt()
            } catch (e: IOException) {
                if (stop.get()) break
                android.util.Log.d("SpeedTest", "A test connection failed: $e")
                Thread.sleep(300)
            }
        }
    }

    private fun mbps(bytes: Long, nanos: Long): Double = if (nanos <= 0) 0.0 else bytes * 8.0 / (nanos / 1e9) / 1e6

    // ---- The last result for each profile ----------------------------------------------

    private const val PREFS = "shieldvpn_speed"

    fun load(context: Context): Map<String, Result> =
        prefs(context).all.mapNotNull { (key, value) ->
            val p = (value as? String)?.split(';') ?: return@mapNotNull null
            runCatching { key to Result(p[0].toInt(), p[1].toDouble(), p[2].toDouble(), p[3].toLong()) }.getOrNull()
        }.toMap()

    fun save(context: Context, key: String, r: Result) {
        prefs(context).edit().putString(key, "${r.pingMs};${r.downMbps};${r.upMbps};${r.at}").apply()
    }

    fun forget(context: Context, key: String?) {
        prefs(context).edit().apply { if (key == null) clear() else remove(key) }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
