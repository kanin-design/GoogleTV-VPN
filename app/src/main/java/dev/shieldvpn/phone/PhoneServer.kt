package dev.shieldvpn.phone

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wireguard.crypto.Curve25519
import dev.shieldvpn.http.HttpServer
import dev.shieldvpn.http.Request
import dev.shieldvpn.remote.Seal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone pages, one per TV screen, on the app's [HttpServer]. They are answered only while a
 * screen shows a phone code (and for two minutes after, so a page can report what happened), and
 * only while the screens are in front.
 *
 * Each TV screen a phone can help with owns a [PhoneSession] with its own six-digit code, and
 * describes its page as a [PhoneForm]. The phone's page draws that form, sends what is typed by
 * field name, and keeps asking what changed, so the TV and the phone show the same thing. The
 * empty page is served to anyone, as it holds nothing; every other request needs a live code.
 * Wrong codes are counted per phone in a ten-minute window that a right code does not reset, and
 * many wrong codes from anywhere give every idle screen a new code.
 *
 * What a page sends and gets is private on a shared Wi-Fi, as passwords and keys go this way: the
 * page and the TV agree a key with X25519 when it loads ([hello]), and each request and answer is
 * sealed with it as the panel's are ([Seal]). The code travels only inside the seal, and the QR
 * code's link carries it in the fragment, which the browser never sends.
 */
class PhoneServer(private val noble: () -> String) : HttpServer.Route {
    /** "192.168.1.20:8080" while the server runs, or null without a home network. */
    val address: String? get() = HttpServer.address

    // While the screens are hidden, a page gets no answer, as if the server were off.
    @Volatile private var inFront = false
    private val sessions = ConcurrentHashMap<String, PhoneSession>()
    private val main = Handler(Looper.getMainLooper())

    /** Codes of sessions that are over, so a phone still holding one is told so instead of "wrong code". */
    private val ended = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** A page's key, from its [hello]: the two directions' keys and the counters it has used. */
    private class Channel(val keys: Seal.Keys) {
        val window = Seal.Window(0)
        @Volatile var used = System.currentTimeMillis()
    }

    private val channels = LinkedHashMap<String, Channel>()

    /** Wrong codes by phone, and from anywhere, in the last ten minutes. */
    private val wrong = HashMap<String, Tries>()
    private val allWrong = ArrayDeque<Long>()

    /** One phone's wrong codes: when each came, until when it must wait, and how often it had to. */
    private class Tries {
        val at = ArrayDeque<Long>()
        var until = 0L
        var strikes = 0
    }

    /** Lets the server go once no screen has shown a code for a while. */
    private val idleStop = Runnable { if (!needed()) HttpServer.release(HOLDER) }

    /** Some screen shows a code, or a session still answers for what a phone did. */
    private fun needed(): Boolean {
        val now = System.currentTimeMillis()
        return sessions.values.any { it.owners > 0 || it.holdUntil > now }
    }

    override fun takes(path: String): Boolean = path == "/" || (path.startsWith("/api/") && !path.startsWith("/api/v2/"))

    override fun stopped() {
        // Pages waiting for a change get an answer now instead of a dead connection later.
        sessions.values.forEach { it.bump() }
    }

    /** The screens are in front: pages are answered, and the server listens while a screen needs it. Main thread only. */
    fun start() {
        inFront = true
        HttpServer.add(this)
        if (needed()) HttpServer.hold(HOLDER)
    }

    /** The screens are hidden. Sessions keep their codes, so pages left open work again after [start]. */
    fun stop() {
        inFront = false
        main.removeCallbacks(idleStop)
        HttpServer.release(HOLDER)
        stopped()
    }

    /** The screens have gone for good: this server answers nothing more, and a new one takes its place. */
    fun close() {
        stop()
        HttpServer.remove(this)
    }

    /** Moves to the new address after the TV changed networks. */
    fun recheck() = HttpServer.recheck()

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
        if (inFront) HttpServer.hold(HOLDER)
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
    fun url(s: PhoneSession): String? = address?.let { "http://$it/#c=${s.code}" }

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

    override fun handle(r: Request) {
        // Hidden screens: no answer, as before the server was shared.
        if (!inFront) return
        val path = r.path
        if ((r.method == "GET" || r.head) && path == "/") {
            return r.send(200, "text/html; charset=utf-8", PhonePage.shell(noble).toByteArray(), "Content-Security-Policy" to CSP, head = r.head)
        }
        if (r.method != "POST" || path != "/api/hello" && path != "/api/x") {
            r.skipBody()
            return r.text(404, "Not found.", r.head)
        }
        if (!r.fromPage) {
            r.skipBody()
            return r.json(403, JSONObject().put("error", "origin").put("text", "Open the address shown on the TV."))
        }
        tooMany(r.from)?.let {
            r.skipBody()
            return r.json(429, JSONObject().put("error", "wait").put("text", it))
        }
        if (path == "/api/hello") hello(r) else sealed(r)
    }

    /** A page that has just loaded sends its X25519 public key; the TV answers with its own, and both make the page's keys. */
    private fun hello(r: Request) {
        if (r.length !in 1..1024) {
            r.skipBody()
            return r.json(413, JSONObject().put("error", "size"))
        }
        val phone = r.body(1024)?.let { runCatching { JSONObject(String(it)) }.getOrNull() }?.optString("p")?.let(Seal::unb64)?.takeIf { it.size == 32 }
            ?: return r.json(400, JSONObject().put("error", "json").put("text", "The TV could not read that."))
        val secret = Seal.randomBytes(32).also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = (it[31].toInt() and 127 or 64).toByte()
        }
        val tv = ByteArray(32).also { Curve25519.eval(it, 0, secret, null) }
        val shared = ByteArray(32).also { Curve25519.eval(it, 0, secret, phone) }
        // A key of a small order makes the shared key known to anyone: no page sends one.
        if (shared.all { it == 0.toByte() }) return r.json(400, JSONObject().put("error", "json").put("text", "The TV could not read that."))
        val id = Seal.b64url(Seal.randomBytes(9))
        val channel = Channel(Seal.Keys.of(shared + phone + tv, "phone"))
        synchronized(channels) {
            channels[id] = channel
            while (channels.size > MAX_CHANNELS) channels.remove(channels.minBy { it.value.used }.key)
        }
        r.json(200, JSONObject().put("h", id).put("t", Seal.b64(tv)))
    }

    /** A sealed request: opened with its page's keys, answered by [answer], and the answer sealed back. */
    private fun sealed(r: Request) {
        val channel = synchronized(channels) { channels[r.query["h"].orEmpty()] }
        if (channel == null) {
            r.skipBody()
            return r.json(401, JSONObject().put("error", "hello"))
        }
        val n = r.query["n"]?.toLongOrNull() ?: 0L
        if (r.length !in 17..MAX_SEALED) {
            r.skipBody()
            return r.json(413, JSONObject().put("error", "size").put("text", "That file is too large. A profile, backup or certificate is much smaller."))
        }
        val body = r.body(MAX_SEALED) ?: return
        val plain = synchronized(channel) {
            if (!channel.window.fresh(n)) return r.json(401, JSONObject().put("error", "counter").put("next", channel.window.top + 1))
            Seal.open(channel.keys.up, Seal.UP, n, AAD, body)?.also {
                channel.window.mark(n)
                channel.used = System.currentTimeMillis()
            }
        }
        if (plain == null) {
            countWrong(r.from)
            return r.json(401, JSONObject().put("error", "seal"))
        }
        val req = runCatching { JSONObject(String(plain)) }.getOrNull()
        val (status, answer) = if (req == null) 400 to JSONObject().put("error", "json").put("text", "The TV could not read that.") else answer(r, req)
        val out = Seal.seal(channel.keys.down, Seal.DOWN, n, AAD, JSONObject().put("s", status).put("j", answer).toString().toByteArray())
        r.send(200, "application/octet-stream", out, "Cache-Control" to "no-store")
    }

    /** What the page asked for in [req], {"c": code, "op": …}, as a status and its JSON. */
    private fun answer(r: Request, req: JSONObject): Pair<Int, JSONObject> {
        val from = r.from
        val code = req.optString("c").filter(Char::isDigit)
        val session = sessions.values.firstOrNull { it.code == code }
        if (session == null) {
            if (code in ended) return 410 to JSONObject().put("error", "ended").put("text", "This code has ended. Scan the new one on the TV.")
            countWrong(from)
            return 403 to JSONObject().put("error", "code").put("text", "That code isn’t on the TV now. Check the code on the TV.")
        }
        session.seen()
        r.waitUpTo(15_000)
        val moved = JSONObject().put("ok", false).put("moved", true)
        return when (req.optString("op")) {
            "state" -> {
                val seen = if (req.has("rev")) req.optInt("rev") else null
                if (seen != null && seen == session.revision) {
                    r.waitUpTo(WAIT_MS.toInt() + 10_000)
                    session.await(seen, WAIT_MS) { !inFront || !HttpServer.running }
                }
                200 to session.stateJson()
            }
            "act" -> {
                if (session.moved) return 200 to moved.put("rev", session.revision)
                val action = req.optString("action")
                val sent = req.optJSONObject("values")?.let { v -> v.keys().asSequence().associateWith { v.optString(it) } } ?: emptyMap()
                val section = req.optString("section").ifEmpty { null }
                val reply = onMain(session) {
                    // Only the fields this page has, each no longer than the page allows.
                    val values = session.clean(sent)
                    session.phoneAction(section, null)
                    val lineBefore = session.tvLine
                    session.onAction(session, action, values).also { reply ->
                        if (reply.holdMs > 0) session.holdFor(reply.holdMs)
                        // The TV shows what the phone did, unless the task said it already.
                        if (session.tvLine == lineBefore && reply.status?.kind == PhoneStatus.Kind.Done) session.tvLine = reply.tv ?: "Saved from your phone"
                        session.phoneAction(section, reply)
                    }
                } ?: PhoneReply.error(null, "The TV took too long to answer. Try again.")
                200 to session.applyReply(reply, section)
            }
            "file" -> {
                val field = req.optString("field")
                val bytes = Seal.unb64(req.optString("data")) ?: return 400 to JSONObject().put("error", "json").put("text", "The TV could not read that file.")
                if (bytes.size > MAX_FILE) return 200 to PhoneReply.error(field, "That file is too large. A profile, backup or certificate is much smaller.").toJson(session.revision)
                if (session.moved) return 200 to moved.put("rev", session.revision)
                val name = req.optString("name").take(120)
                val reply = onMain(session) {
                    session.onUpload(session, field, name, bytes).also { if (it.holdMs > 0) session.holdFor(it.holdMs) }
                } ?: PhoneReply.error(field, "The TV took too long to answer. Try again.")
                200 to session.applyReply(reply, req.optString("section").ifEmpty { null })
            }
            "input" -> {
                if (!session.moved) {
                    val field = req.optString("field")
                    val value = req.optString("value").take(MAX_INPUT)
                    main.post { session.typed(field, value) }
                }
                200 to JSONObject().put("ok", true)
            }
            "download" -> {
                val id = req.optString("id")
                val file = onMain(session) { session.download(session, id) }
                    ?: return 404 to JSONObject().put("error", "file").put("text", "That file isn’t on the TV now.")
                file.refusal?.let { return 403 to JSONObject().put("error", "refused").put("text", it) }
                // Kept by the session, so the line stays when the page is drawn again.
                session.say(PhoneStatus(PhoneStatus.Kind.Done, "Downloaded ${file.name}. Look in Files › Downloads on your phone.", req.optString("section").ifEmpty { null }))
                main.post { session.onDownloaded(session, id, file.name) }
                200 to JSONObject().put("name", file.name).put("mime", file.mime).put("data", Seal.b64(file.bytes))
            }
            else -> 404 to JSONObject().put("error", "op").put("text", "Not found.")
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

    companion object {
        private const val HOLDER = "phone pages"
        private const val MAX_FILE = 512 * 1024
        // A file goes as base64 in its request, and sealed.
        private const val MAX_SEALED = MAX_FILE * 4 / 3 + 64 * 1024
        private const val MAX_INPUT = 4096
        private const val MAX_CHANNELS = 64
        private val AAD = "POST /api/x".toByteArray()
        private const val WAIT_MS = 25_000L
        private const val ANSWER_MS = 45_000L
        private const val WINDOW_MS = 10 * 60_000L

        /** A session no screen shows any more keeps its code this long, so the TV can come back to it. */
        const val KEEP_MS = 2 * 60_000L

        // The page needs only itself: its own script and style, pictures it makes, and requests to the TV.
        private const val CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; " +
            "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
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

    /** What a phone did last, for the TV's status line: "Received Home.ovpn". A new task on the TV clears it. */
    var tvLine: String?
        get() = tvLineNow
        set(value) {
            tvLineNow = value
            tvLineAt = System.currentTimeMillis()
        }
    private var tvLineNow by mutableStateOf<String?>(null)
    @Volatile private var tvLineAt = 0L

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

    /** Who follows a phone's action on a section: a null reply when it starts, then its reply. */
    private val watchers = mutableListOf<(String?, PhoneReply?) -> Unit>()

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
                focusNow = section
                moved = true
                true
            } else {
                false
            }
        }
        // A new sheet on the TV is a new task: the last one's result line goes.
        if (moved && section != null) newTask()
        if (moved) rebuild()
    }

    /**
     * The TV starts another task with this session (another sheet, another Import method): the last
     * task's result lines go, on the TV and the phone, unless they are only just in, from the phone
     * action that started this one. Main thread only.
     */
    fun newTask() {
        val now = System.currentTimeMillis()
        if (tvLineNow != null && now - tvLineAt > 3000) tvLine = null
        change { if (statusNow != null && now - statusAt > 3000) { statusNow = null; true } else false }
    }

    fun listenActions(listener: (section: String?, reply: PhoneReply?) -> Unit): () -> Unit {
        watchers += listener
        return { watchers -= listener }
    }

    /** A phone's action on [section] starts (null [reply]) or has its reply. Main thread only. */
    internal fun phoneAction(section: String?, reply: PhoneReply?) {
        watchers.toList().forEach { it(section, reply) }
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
