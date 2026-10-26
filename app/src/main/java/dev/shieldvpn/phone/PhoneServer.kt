package dev.shieldvpn.phone

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The web server a phone talks to, one for the app. It listens only while a screen shows a phone
 * code (and for two minutes after, so a page can report what happened), and only on the home
 * network's address.
 *
 * Each TV screen a phone can help with owns a [PhoneSession] with its own six-digit code, and
 * describes its page as a [PhoneForm]. The phone's page draws that form, sends what is typed by
 * field name, and keeps asking what changed, so the TV and the phone show the same thing. The
 * empty page is served to anyone, as it holds nothing; every other request needs a live code.
 * Wrong codes are counted per phone in a ten-minute window that a right code does not reset, and
 * many wrong codes from anywhere give every idle screen a new code.
 */
class PhoneServer {
    /** "192.168.1.20:8080" while the server runs, or null without a home network. */
    var address by mutableStateOf<String?>(null)
        private set

    private var socket: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private var boundIp: String? = null
    private val sessions = ConcurrentHashMap<String, PhoneSession>()
    private val main = Handler(Looper.getMainLooper())

    /** Codes of sessions that are over, so a phone still holding one is told so instead of "wrong code". */
    private val ended = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** Wrong codes by phone, and from anywhere, in the last ten minutes. */
    private val wrong = HashMap<String, Tries>()
    private val allWrong = ArrayDeque<Long>()

    /** One phone's wrong codes: when each came, until when it must wait, and how often it had to. */
    private class Tries {
        val at = ArrayDeque<Long>()
        var until = 0L
        var strikes = 0
    }

    /** Stops the server once no screen has shown a code for a while. */
    private val idleStop = Runnable { if (!needed()) stop() }

    /** Some screen shows a code, or a session still answers for what a phone did. */
    private fun needed(): Boolean {
        val now = System.currentTimeMillis()
        return sessions.values.any { it.owners > 0 || it.holdUntil > now }
    }

    /** Starts listening on the home network's address, if a screen needs it. Main thread only. */
    fun start() {
        if (socket != null || !needed()) return
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
        val threads = ThreadPoolExecutor(4, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue())
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
                try {
                    threads.execute { runCatching { handle(s) }.onFailure { Log.w(TAG, "request failed: ${it.message}") } }
                } catch (_: RejectedExecutionException) {
                    runCatching { s.close() }
                }
            }
        }, "phone-server").start()
    }

    /** Stops listening. Sessions keep their codes, so pages left open work again after [start]. */
    fun stop() {
        main.removeCallbacks(idleStop)
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
        boundIp = null
        address = null
        // Pages waiting for a change get an answer now instead of a dead connection later.
        sessions.values.forEach { it.bump() }
    }

    /** Moves to the new address after the TV changed networks, or starts once there is one. */
    fun recheck() {
        if (!needed()) return
        if (lanAddress() == boundIp && socket != null) return
        stop()
        start()
    }

    /** The session of screen [key], made with a new code if there is none. Call [hold] and [release] around its use. */
    fun session(key: String): PhoneSession {
        sweep()
        return sessions.getOrPut(key) { PhoneSession(key, newCode()) }
    }

    /** The session of screen [key] if there is one, for a view that only shows its code. */
    fun existing(key: String): PhoneSession? = sessions[key]

    /** A screen is showing [s]. Main thread only. */
    fun hold(s: PhoneSession) {
        s.owners++
        s.bump()
        main.removeCallbacks(idleStop)
        start()
    }

    /** A screen showing [s] has gone. With none left, the phone hears that the TV moved on. Main thread only. */
    fun release(s: PhoneSession) {
        s.owners = (s.owners - 1).coerceAtLeast(0)
        if (s.owners == 0) {
            s.leftAt = System.currentTimeMillis()
            // What the screen did with a phone's request is gone with it, unless the page goes on without it.
            if (!s.keepsWorking) s.detach()
        }
        s.bump()
        if (sessions.values.none { it.owners > 0 }) {
            main.removeCallbacks(idleStop)
            main.postDelayed(idleStop, KEEP_MS + 1_000)
        }
    }

    /** The address to open on a phone for [s], with its code; null without a home network. */
    fun url(s: PhoneSession): String? = address?.let { "http://$it/?c=${s.code}" }

    /** Ends sessions no screen has shown for two minutes. */
    private fun sweep() {
        val now = System.currentTimeMillis()
        sessions.values.filter { it.owners == 0 && now - maxOf(it.leftAt, it.holdUntil) > KEEP_MS }.forEach {
            sessions.remove(it.key)
            ended += it.code
            it.bump()
        }
        synchronized(ended) { while (ended.size > 64) ended.remove(ended.first()) }
    }

    private fun newCode(): String {
        val random = SecureRandom()
        while (true) {
            val code = (random.nextInt(900_000) + 100_000).toString()
            if (sessions.values.none { it.code == code } && code !in ended) return code
        }
    }

    // ---- Requests ------------------------------------------------------------------

    private fun handle(client: Socket): Unit = client.use { s ->
        s.soTimeout = 3_000
        val from = s.inetAddress?.hostAddress.orEmpty()
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            headerBytes += line.length
            // Before any code is checked, a request gets only so much room.
            if (headers.size >= MAX_HEADERS || headerBytes > MAX_HEADER_BYTES) return text(out, 400, "Too many headers.")
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val head = method == "HEAD"
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: -1 } ?: 0
        if (length < 0) return text(out, 400, "A length is needed.")
        // A phone still sending sees a reset instead of the answer if the body is left unread.
        val skipBody = { if (length in 1..MAX_DRAIN) drain(input, length) }

        // The page is reached by the TV's address. A name in the Host header is another site
        // pointing its name at this TV (DNS rebinding), which is refused.
        val host = headers["host"]?.substringBeforeLast(':')
        if (host == null || !IPV4.matches(host)) {
            skipBody()
            return text(out, 403, "Open the address shown on the TV.")
        }
        if ((method == "GET" || head) && path == "/") {
            return send(out, 200, "text/html; charset=utf-8", PhonePage.shell.toByteArray(), "Content-Security-Policy" to CSP, head = head)
        }
        if (!path.startsWith("/api/")) {
            skipBody()
            return text(out, 404, "Not found.", head)
        }
        // Only the page sends this header; a form on another site can't, without a preflight this server never answers.
        if (method == "POST" && headers["x-phone"] != "1") {
            skipBody()
            return json(out, 403, JSONObject().put("error", "origin").put("text", "Open the address shown on the TV."))
        }
        tooMany(from)?.let {
            skipBody()
            return json(out, 429, JSONObject().put("error", "wait").put("text", it))
        }
        val code = query["c"].orEmpty().filter(Char::isDigit)
        val session = sessions.values.firstOrNull { it.code == code }
        if (session == null) {
            skipBody()
            if (code in ended) return json(out, 410, JSONObject().put("error", "ended").put("text", "This code has ended. Scan the new one on the TV."))
            countWrong(from)
            return json(out, 403, JSONObject().put("error", "code").put("text", "That code isn’t on the TV now. Check the code on the TV."))
        }
        session.seen()
        s.soTimeout = 15_000

        when {
            method == "GET" && path == "/api/state" -> {
                val seen = query["rev"]?.toIntOrNull()
                if (seen != null && seen == session.revision) session.await(seen, WAIT_MS) { this.socket == null }
                json(out, 200, session.stateJson())
            }
            method == "POST" && path == "/api/act" -> {
                if (length !in 1..MAX_JSON) {
                    skipBody()
                    return json(out, 413, JSONObject().put("error", "size").put("text", "That is too much text."))
                }
                val body = runCatching { JSONObject(String(readExactly(input, length) ?: return)) }.getOrNull()
                    ?: return json(out, 400, JSONObject().put("error", "json").put("text", "The TV could not read that."))
                if (session.moved) return json(out, 200, JSONObject().put("ok", false).put("moved", true).put("rev", session.revision))
                val action = body.optString("action")
                val sent = body.optJSONObject("values")?.let { v -> v.keys().asSequence().associateWith { v.optString(it) } } ?: emptyMap()
                val reply = onMain(session) {
                    // Only the fields this page has, each no longer than the page allows.
                    val values = session.clean(sent)
                    session.onAction(session, action, values).also { if (it.holdMs > 0) session.holdFor(it.holdMs) }
                } ?: PhoneReply.error(null, "The TV took too long to answer. Try again.")
                json(out, 200, session.applyReply(reply, body.optString("section").ifEmpty { null }))
            }
            method == "POST" && path == "/api/file" -> {
                val field = query["field"].orEmpty()
                if (length > MAX_FILE) {
                    skipBody()
                    return json(out, 200, PhoneReply.error(field, "That file is too large. A profile, backup or certificate is much smaller.").toJson(session.revision))
                }
                val bytes = readExactly(input, length) ?: return
                if (session.moved) return json(out, 200, JSONObject().put("ok", false).put("moved", true).put("rev", session.revision))
                val name = query["name"].orEmpty().take(120)
                val reply = onMain(session) {
                    session.onUpload(session, field, name, bytes).also { if (it.holdMs > 0) session.holdFor(it.holdMs) }
                } ?: PhoneReply.error(field, "The TV took too long to answer. Try again.")
                json(out, 200, session.applyReply(reply, query["section"]))
            }
            method == "POST" && path == "/api/input" -> {
                if (length !in 1..MAX_JSON) {
                    skipBody()
                    return json(out, 413, JSONObject().put("error", "size").put("text", "That is too much text."))
                }
                val body = runCatching { JSONObject(String(readExactly(input, length) ?: return)) }.getOrNull()
                if (body != null && !session.moved) {
                    val field = body.optString("field")
                    val value = body.optString("value")
                    main.post { session.typed(field, value) }
                }
                json(out, 200, JSONObject().put("ok", true))
            }
            method == "GET" && path == "/api/download" -> {
                val id = query["id"].orEmpty()
                val file = onMain(session) { session.download(session, id) }
                    ?: return json(out, 404, JSONObject().put("error", "file").put("text", "That file isn’t on the TV now."))
                file.refusal?.let { return json(out, 403, JSONObject().put("error", "refused").put("text", it)) }
                send(out, 200, file.mime, file.bytes, "Content-Disposition" to disposition(file.name))
                // Kept by the session, so the line stays when the page is drawn again.
                session.say(PhoneStatus(PhoneStatus.Kind.Done, "Downloaded ${file.name}. Look in Files › Downloads on your phone.", query["section"]))
                main.post { session.onDownloaded(session, id, file.name) }
            }
            else -> {
                skipBody()
                text(out, 404, "Not found.", head)
            }
        }
    }

    /** Runs [block] on the main thread, where the screens' state lives, waits for its answer, then lets the screen draw its page again. */
    private fun <T> onMain(session: PhoneSession, block: suspend () -> T): T? = runBlocking {
        withTimeoutOrNull(ANSWER_MS) {
            withContext(Dispatchers.Main) {
                block().also { session.rebuild() }
            }
        }
    }

    private fun countWrong(from: String) {
        val now = System.currentTimeMillis()
        var renew = false
        synchronized(wrong) {
            val t = wrong.getOrPut(from) { Tries() }
            t.at.addLast(now)
            while (t.at.isNotEmpty() && t.at.first() < now - WINDOW_MS) t.at.removeFirst()
            if (t.at.size >= 5) {
                // Each wait is twice the one before, up to sixteen minutes.
                t.until = now + (60_000L shl t.strikes.coerceAtMost(4))
                t.strikes++
                t.at.clear()
            }
            allWrong.addLast(now)
            while (allWrong.isNotEmpty() && allWrong.first() < now - WINDOW_MS) allWrong.removeFirst()
            if (allWrong.size >= 30) {
                allWrong.clear()
                renew = true
            }
        }
        // Many phones guessing at once: every screen no phone is using gets a new code.
        if (renew) main.post { renewIdleCodes() }
    }

    private fun renewIdleCodes() {
        val now = System.currentTimeMillis()
        sessions.values.filter { now - it.lastSeen > 40_000 }.forEach {
            ended += it.code
            it.code = newCode()
            it.bump()
        }
    }

    /** Why this phone has to wait, after too many wrong codes, or null. */
    private fun tooMany(from: String): String? {
        val until = synchronized(wrong) { wrong[from]?.until } ?: return null
        return if (System.currentTimeMillis() < until) "Too many wrong codes. Wait a while, then try again with the code on the TV." else null
    }

    /** The download's name, kept with its letters for phones that read it, and plain for those that don't. */
    private fun disposition(name: String): String {
        val plain = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        return "attachment; filename=\"$plain\"; filename*=UTF-8''$encoded"
    }

    private fun json(out: OutputStream, status: Int, body: JSONObject) =
        send(out, status, "application/json; charset=utf-8", body.toString().toByteArray())

    private fun text(out: OutputStream, status: Int, body: String, head: Boolean = false) =
        send(out, status, "text/plain; charset=utf-8", body.toByteArray(), head = head)

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, vararg extra: Pair<String, String>, head: Boolean = false) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            410 -> "Gone"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
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

    companion object {
        private const val TAG = "PhoneServer"
        private const val MAX_THREADS = 24
        private const val MAX_FILE = 512 * 1024
        private const val MAX_JSON = 256 * 1024
        private const val MAX_HEADERS = 64
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val WAIT_MS = 25_000L
        private const val ANSWER_MS = 45_000L
        private const val WINDOW_MS = 10 * 60_000L

        /** A session no screen shows any more keeps its code this long, so the TV can come back to it. */
        const val KEEP_MS = 2 * 60_000L

        /** Up to this much of a refused upload is read and dropped, so the phone gets the answer. */
        private const val MAX_DRAIN = 8 * 1024 * 1024

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        // The page needs only itself: its own script and style, pictures it makes, and requests to the TV.
        private const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; " +
            "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

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

/**
 * One TV screen's link to a phone: its code, the form its page shows, and what the screen does
 * with what the phone sends. The screen updates [form] as it changes; every change wakes the
 * phone's page, which then draws the new form.
 */
class PhoneSession internal constructor(val key: String, code: String) {
    private val lock = Object()

    /** The code, changed only when many wrong codes were typed somewhere. */
    var code by mutableStateOf(code)
        internal set

    // The page's state, changed together under [lock], so a page never gets a form with another's revision.
    private var rev = 1
    private var formNow: PhoneForm? = null
    private var statusNow: PhoneStatus? = null
    private var statusAt = 0L
    private var focusNow: String? = null

    val form: PhoneForm? get() = synchronized(lock) { formNow }

    /** The section the TV is editing now, which the phone's page points out. */
    val focus: String? get() = synchronized(lock) { focusNow }

    internal val revision: Int get() = synchronized(lock) { rev }

    /** When a phone last asked, for the TV's status line. */
    var lastSeen by mutableLongStateOf(0L)
        private set

    /** What a phone did last, for the TV's status line: "Received Home.ovpn". */
    var tvLine by mutableStateOf<String?>(null)

    @Volatile internal var owners = 0
    @Volatile internal var leftAt = System.currentTimeMillis()

    /** After a phone's action, the session keeps answering for its outcome until then, though the screen closed. */
    @Volatile internal var holdUntil = 0L

    /** Its page goes on working after the screen has gone (Import's "Add another", Sign-in's retry). */
    internal var keepsWorking = false

    /** No screen shows this session: the phone's page says the TV moved on. */
    val moved: Boolean get() = owners == 0 && System.currentTimeMillis() >= holdUntil

    internal var onAction: suspend PhoneSession.(String, Map<String, String>) -> PhoneReply = NO_ACTION
    internal var onUpload: suspend PhoneSession.(String, String, ByteArray) -> PhoneReply = NO_UPLOAD
    internal var onDownloaded: PhoneSession.(String, String) -> Unit = { _, _ -> }
    internal var download: suspend PhoneSession.(String) -> PhoneFile? = { null }

    /** Builds the page again from the app's state as it is now, after a phone's request. */
    internal var rebuild: () -> Unit = {}

    /** Who listens to what is typed on the phone as it is typed: the sheet editing those fields. */
    private val typists = mutableListOf<(String, String) -> Unit>()

    /** Who listens for a section the phone saved, so a TV sheet editing it can close. */
    private val savers = mutableListOf<(String) -> Unit>()

    /** Forgets the screen's handlers, so a page left open can't act through a screen that has gone. */
    internal fun detach() {
        onAction = NO_ACTION
        onUpload = NO_UPLOAD
        onDownloaded = { _, _ -> }
        download = { null }
        rebuild = {}
    }

    /** What the phone typed in [field] as it types, for a page field that mirrors to the TV. */
    internal fun typed(field: String, value: String) {
        val text = when (val f = form?.sections?.flatMap { it.fields }?.firstOrNull { it.id == field }) {
            is PhoneField.Text -> value.take(f.max).takeIf { f.mirror }
            // The TV's sheet takes them on one line.
            is PhoneField.Lines -> value.take(MAX_LINES_TEXT).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").takeIf { f.mirror }
            else -> null
        } ?: return
        typists.toList().forEach { it(field, text) }
    }

    /** The values a phone sent, limited to the fields its page has and the length each allows. */
    internal fun clean(sent: Map<String, String>): Map<String, String> {
        val known = form?.sections.orEmpty().flatMap { it.fields }.associateBy { it.id }
        return sent.filterKeys { it in known }.mapValues { (k, v) ->
            when (val f = known.getValue(k)) {
                is PhoneField.Text -> v.take(f.max)
                is PhoneField.Lines -> v.take(MAX_LINES_TEXT)
                else -> v.take(256)
            }
        }
    }

    /** Runs [listener] for each field the phone types into, while it is added. Returns the remover. */
    fun listenTyping(listener: (field: String, value: String) -> Unit): () -> Unit {
        typists += listener
        return { typists -= listener }
    }

    /** Runs [listener] when the phone saves a section. Returns the remover. */
    fun listenSaved(listener: (section: String) -> Unit): () -> Unit {
        savers += listener
        return { savers -= listener }
    }

    /** Tells the sheets listening that the phone saved [section]. */
    fun saved(section: String) {
        savers.toList().forEach { it(section) }
    }

    fun update(next: PhoneForm) = change { if (next != formNow) { formNow = next; true } else false }

    /** Shows [next] on the phone, or clears what it shows. */
    fun say(next: PhoneStatus?) = change { if (next != statusNow) { statusNow = next; statusAt = System.currentTimeMillis(); true } else false }

    /**
     * A new task on the TV: the last one's result line goes, unless it is only just in, from the
     * phone action that opened this. Main thread only.
     */
    fun clearOldStatus() = change { if (statusNow != null && System.currentTimeMillis() - statusAt > 3000) { statusNow = null; true } else false }

    /**
     * Points the phone at [section], the part of the form the TV is editing now. The page is built
     * again, as some parts (a download that carries keys) are offered only while the TV points at
     * them. Main thread only.
     */
    fun point(section: String?) {
        var moved = false
        change {
            if (section != focusNow) {
                // A new sheet on the TV is a new task: the last one's result line goes.
                if (section != null && System.currentTimeMillis() - statusAt > 3000) statusNow = null
                focusNow = section
                moved = true
                true
            } else {
                false
            }
        }
        if (moved) rebuild()
    }

    /** Keeps the session answering for [ms] after its screen closes, to report what happened. */
    fun holdFor(ms: Long) {
        holdUntil = System.currentTimeMillis() + ms
    }

    internal fun seen() {
        val now = System.currentTimeMillis()
        // Main-thread state, set from the server's threads; once every few seconds is plenty.
        if (now - lastSeen > 5_000) Handler(Looper.getMainLooper()).post { lastSeen = now }
    }

    private inline fun change(edit: () -> Boolean) {
        synchronized(lock) {
            if (edit()) {
                rev++
                lock.notifyAll()
            }
        }
    }

    internal fun bump() = change { true }

    internal fun await(seen: Int, ms: Long, stop: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        synchronized(lock) {
            while (rev == seen && !stop()) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
        }
    }

    internal fun stateJson(): JSONObject = synchronized(lock) {
        val f = formNow
        val o = JSONObject().put("rev", rev)
        when {
            moved -> o.put("state", "moved").put("place", f?.place ?: "this screen")
            f == null -> o.put("state", "moved").put("place", "this screen")
            else -> o.put("state", "ready").put("form", f.toJson()).put("status", statusNow?.toJson() ?: JSONObject.NULL).put("focus", focusNow ?: JSONObject.NULL)
        }
    }

    internal fun applyReply(reply: PhoneReply, section: String?): JSONObject = synchronized(lock) {
        // What the phone did last replaces what was shown before, also when it has nothing to say.
        statusNow = reply.status?.let { it.copy(section = it.section ?: section) }
        statusAt = System.currentTimeMillis()
        rev++
        lock.notifyAll()
        reply.toJson(rev, section)
    }

    private companion object {
        const val MAX_LINES_TEXT = 4096
        val NO_ACTION: suspend PhoneSession.(String, Map<String, String>) -> PhoneReply = { _, _ -> PhoneReply.error(null, "The TV moved on. Open the screen on the TV again.") }
        val NO_UPLOAD: suspend PhoneSession.(String, String, ByteArray) -> PhoneReply = { field, _, _ -> PhoneReply.error(field, "The TV moved on. Open the screen on the TV again.") }
    }
}

/**
 * A file the phone can download. One that carries keys may be [refused] until the TV asks for it,
 * with the reason for the phone.
 */
class PhoneFile(val name: String, val mime: String, val bytes: ByteArray, val refusal: String? = null) {
    companion object {
        fun refused(reason: String) = PhoneFile("", "", ByteArray(0), reason)
    }
}
