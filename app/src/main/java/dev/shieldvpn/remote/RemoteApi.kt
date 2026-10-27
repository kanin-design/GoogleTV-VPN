package dev.shieldvpn.remote

import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.shieldvpn.APP_FILE_NAME
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.BuildConfig
import dev.shieldvpn.control.Changes
import dev.shieldvpn.control.ConnectionTest
import dev.shieldvpn.control.Control
import dev.shieldvpn.control.CoreState
import dev.shieldvpn.control.Effect
import dev.shieldvpn.control.Option
import dev.shieldvpn.control.PinTries
import dev.shieldvpn.control.ProfileStatus
import dev.shieldvpn.control.Schema
import dev.shieldvpn.control.Verify
import dev.shieldvpn.control.Words
import dev.shieldvpn.http.HttpServer
import dev.shieldvpn.http.Request
import dev.shieldvpn.vpn.CredentialStore
import dev.shieldvpn.vpn.ImportReview
import dev.shieldvpn.vpn.Inspected
import dev.shieldvpn.vpn.LogLine
import dev.shieldvpn.vpn.ServerStore
import dev.shieldvpn.vpn.VpnController
import dev.shieldvpn.vpn.VpnKind
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.connectionId
import dev.shieldvpn.vpn.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The paired phones' panel: its page under /panel/, and its API under /api/v2/. Every request and
 * answer is sealed with the phone's device key ([Seal]), so nothing on the Wi-Fi can read or
 * replay it; pairing is the one request sealed with the QR code's secret instead.
 *
 * A request is {"d": device, "n": counter, "c": sealed JSON}; its answer {"n": counter, "c": sealed
 * JSON}. Errors before the seal opens are plain and say nothing more than what to do next.
 */
object RemoteApi : HttpServer.Route {
    private const val MAX_BODY = 800 * 1024
    private const val MAX_FILE = 512 * 1024
    private const val WAIT_MS = 25_000L
    private const val LIVE_WAIT_MS = 8_000L
    private const val ASK_MS = 90_000L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Changes are applied one at a time, in the order they came. */
    private val applier = Executors.newSingleThreadExecutor { Thread(it, "panel-apply") }
    private val applying = java.util.concurrent.atomic.AtomicInteger()

    /** A phone's change is being applied or tested: the process must not end now. */
    val busy: Boolean get() = applying.get() > 0

    // What the page waits for: a new view of the state, log lines, or progress of a change.
    private val lock = Object()
    private var view = 1L
    private var seq = 0L
    private val events = ArrayDeque<Pair<Long, JSONObject>>()
    private var logLines: List<LogLine> = emptyList()
    private var logStart = 0L
    private var logEpoch = 1

    // Broken seals by address, so a phone guessing keys is slowed down.
    private val broken = HashMap<String, ArrayDeque<Long>>()

    fun start(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        HttpServer.add(this)
        Control.listen { bump() }
        scope.launch { VpnController.status.collect { bump() } }
        scope.launch { VpnController.tunnel.collect { bump() } }
        scope.launch { VpnController.pausedUntil.collect { bump() } }
        scope.launch { Devices.list.collect { bump() } }
        scope.launch { VpnController.log.collect(::logChanged) }
    }

    private fun bump() = synchronized(lock) {
        view++
        lock.notifyAll()
    }

    private fun push(event: JSONObject) = synchronized(lock) {
        seq++
        events.addLast(seq to event.put("seq", seq))
        while (events.size > 200) events.removeFirst()
        if (event.optString("t") == "change") remember(event)
        lock.notifyAll()
    }

    /** The last word on each recent change, for a page that lost track of one. Under [lock]. */
    private val outcomes = LinkedHashMap<String, JSONObject>()

    private fun remember(event: JSONObject) {
        outcomes[event.optString("change")] = event
        while (outcomes.size > 30) outcomes.remove(outcomes.keys.first())
    }

    /** Numbers the log's lines as they come, so a page asks only for what it hasn't got. */
    private fun logChanged(next: List<LogLine>) = synchronized(lock) {
        val prev = logLines
        val last = prev.lastOrNull()
        val at = if (last == null) -1 else next.indexOfLast { it === last }
        if (last != null && at >= 0) {
            logStart += (prev.size - 1) - at
        } else if (prev.isNotEmpty() || next.isEmpty()) {
            logEpoch++
            logStart = 0
        }
        logLines = next
        lock.notifyAll()
    }

    override fun takes(path: String): Boolean = path == "/panel" || path.startsWith("/panel/") || path.startsWith("/api/v2/")

    override fun stopped() = synchronized(lock) { lock.notifyAll() }

    override fun handle(r: Request) {
        if (r.path == "/panel") return r.send(302, "text/plain; charset=utf-8", ByteArray(0), "Location" to "/panel/")
        if (r.path.startsWith("/panel/")) return page(r)
        if (r.method != "POST") {
            r.skipBody()
            return r.text(404, "Not found.")
        }
        // Only the page sends this header; a form on another site can't, without a preflight this server never answers.
        if (!r.fromPage) {
            r.skipBody()
            return r.json(403, JSONObject().put("error", "origin"))
        }
        if (tooManyBroken(r.from)) {
            r.skipBody()
            return r.json(429, JSONObject().put("error", "wait"))
        }
        if (r.length !in 2..MAX_BODY) {
            r.skipBody()
            return r.json(413, JSONObject().put("error", "size"))
        }
        r.waitUpTo(20_000)
        val body = r.body(MAX_BODY)?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: return r.json(400, JSONObject().put("error", "json"))
        val n = body.optLong("n")
        val lane = body.optInt("l", 0)
        if (lane !in 0 until Seal.LANES) return r.json(400, JSONObject().put("error", "json"))
        val sealed = Seal.unb64(body.optString("c")) ?: return r.json(400, JSONObject().put("error", "json"))
        val aad = "POST ${r.path}".toByteArray()
        if (r.path == "/api/v2/pair") return pair(r, body.optString("p"), n, lane, aad, sealed)
        val id = body.optString("d")
        val (device, plain) = when (val o = Devices.open(id, n, lane, aad, sealed)) {
            is Devices.Opened.Ok -> o.device to o.plain
            Devices.Opened.Unknown -> return r.json(401, JSONObject().put("error", "pair"))
            is Devices.Opened.Old -> return r.json(401, JSONObject().put("error", "counter").put("next", o.next))
            Devices.Opened.Broken -> {
                countBroken(r.from)
                return r.json(401, JSONObject().put("error", "seal"))
            }
        }
        val req = runCatching { JSONObject(String(plain)) }.getOrNull() ?: JSONObject()
        val answer = runCatching { answer(r, device, r.path.removePrefix("/api/v2/"), req) }
            .getOrElse { JSONObject().put("ok", false).put("text", "The TV couldn’t do that: ${it.message ?: "something went wrong"}.") }
        val out = Devices.seal(device.id, n, lane, aad, answer.toString().toByteArray()) ?: return r.json(401, JSONObject().put("error", "pair"))
        r.json(200, JSONObject().put("n", n).put("c", Seal.b64(out)))
    }

    private fun answer(r: Request, device: Device, what: String, req: JSONObject): JSONObject = when (what) {
        "state" -> JSONObject().put("ok", true).put("state", onMain { state(device) })
        "events" -> {
            r.waitUpTo((WAIT_MS + 15_000).toInt())
            events(device, req)
        }
        "act" -> act(device, req)
        "schema" -> JSONObject().put("ok", true).put("schema", onMain { launcherApps(); Schema.toJson(Control.state.value) })
        "changes" -> JSONObject(onMain { Changes.check(req).toJson().toString() }).put("ok", true)
        "changes/apply" -> apply(device, req.optString("change"), req.optBoolean("test"), req.optString("pin").ifEmpty { null })
        // Null after the TV's app restarted, which forgets every change it was making.
        "changes/state" -> JSONObject().put("ok", true).put("event", synchronized(lock) { outcomes[req.optString("change")] } ?: JSONObject.NULL)
        "files" -> file(req)
        "download" -> download(device, req)
        "devices/remove" -> removeDevice(device, req.optString("id"), req)
        else -> JSONObject().put("ok", false).put("text", "The TV doesn’t know that request.")
    }

    // ---- The page ------------------------------------------------------------------------

    private val assets = HashMap<String, ByteArray>()
    private val tags = HashMap<String, String>()

    private fun page(r: Request) {
        r.skipBody()
        val name = r.path.removePrefix("/panel/").ifEmpty { "index.html" }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            else -> return r.text(404, "Not found.")
        }
        if (name !in setOf("index.html", "panel.js", "panel.css", "noble.js", "icon.svg")) return r.text(404, "Not found.")
        val bytes = synchronized(assets) {
            assets.getOrPut(name) { runCatching { app.assets.open("panel/$name").use { it.readBytes() } }.getOrNull() ?: return r.text(404, "Not found.") }
        }
        // From the file itself, so a rebuild under the same build number still serves its new files.
        val tag = synchronized(assets) { tags.getOrPut(name) { "\"" + java.util.zip.CRC32().also { it.update(bytes) }.value.toString(16) + "\"" } }
        if (r.headers["if-none-match"] == tag) return r.send(304, type, ByteArray(0), "ETag" to tag, "Cache-Control" to "no-cache")
        r.send(200, type, bytes, "ETag" to tag, "Cache-Control" to "no-cache", "Content-Security-Policy" to CSP, head = r.head)
    }

    // Only its own files: no inline script, nothing from elsewhere.
    private const val CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; " +
        "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    // ---- Pairing -------------------------------------------------------------------------

    private fun pair(r: Request, offerId: String, n: Long, lane: Int, aad: ByteArray, sealed: ByteArray) {
        val o = Pairing.current(offerId)?.takeIf { !it.expired }
            ?: return r.json(401, JSONObject().put("error", "pairing"))
        val plain = synchronized(o) {
            if (!o.window.fresh(n)) return r.json(401, JSONObject().put("error", "counter").put("next", o.window.top + 1))
            val p = Seal.open(o.keys.up, Seal.UP, n, aad, sealed, lane) ?: run {
                countBroken(r.from)
                return r.json(401, JSONObject().put("error", "seal"))
            }
            o.window.mark(n)
            p
        }
        val req = runCatching { JSONObject(String(plain)) }.getOrNull() ?: JSONObject()
        val claim = req.optString("claim")
        // Numbered when a paired phone has the name already; the phone hears the name it got.
        val name = Devices.uniqueName(req.optString("name"))
        var askNow = false
        val answer = synchronized(o) {
            when {
                o.claim == null -> {
                    o.claim = claim
                    o.name = name
                    null
                }
                o.claim != claim -> JSONObject().put("state", "taken").put("text", "Another phone is pairing with this code. Show a new code on the TV.")
                else -> null
            }.also {
                // Asked once, though the phone asks again while it waits.
                if (it == null && !o.asked) {
                    o.asked = true
                    askNow = true
                }
            }
        } ?: run {
            if (askNow) {
                Thread({
                    // Not now first, as for every other question a phone causes: one OK hands it everything.
                    val ask = Asks.ask(
                        "Settings › Phone access", "Pair “$name”?",
                        "From ${r.from}. It can see and change $APP_NAME from its browser, on your home network. Pair only a phone you know.", "Pair",
                        danger = true, paired = false,
                    )
                    val yes = Asks.await(ask, ASK_MS) == true
                    val result = if (yes) Devices.add(name).let { (d, key) -> Pairing.Result.Paired(d, key) } else Pairing.Result.Refused
                    Pairing.finished(o, result)
                    if (yes) main.post { Control.remoteChanged() }
                    synchronized(lock) { lock.notifyAll() }
                }, "panel-pair").start()
            }
            r.waitUpTo(40_000)
            val end = System.currentTimeMillis() + 20_000
            synchronized(lock) {
                while (o.result == null && System.currentTimeMillis() < end && HttpServer.running) lock.wait(1_000)
            }
            when (val result = o.result) {
                is Pairing.Result.Paired -> JSONObject().put("state", "paired").put("device", result.device.id).put("key", Seal.b64(result.key))
                    .put("name", result.device.name).put("tv", tvName())
                Pairing.Result.Refused -> JSONObject().put("state", "refused").put("text", "Not paired: the TV said Not now.")
                null -> JSONObject().put("state", "asking").put("text", "Press Pair on the TV.")
            }
        }
        val out = Seal.seal(o.keys.down, Seal.DOWN, n, aad, answer.toString().toByteArray(), lane)
        r.json(200, JSONObject().put("n", n).put("c", Seal.b64(out)))
    }

    /** The name the TV was given in Android's settings, as the Wi-Fi and casting show it. */
    private fun tvName(): String =
        runCatching { android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "TV"

    private fun countBroken(from: String) = synchronized(broken) {
        val now = System.currentTimeMillis()
        val q = broken.getOrPut(from) { ArrayDeque() }
        q.addLast(now)
        while (q.isNotEmpty() && q.first() < now - 10 * 60_000L) q.removeFirst()
        if (broken.size > 256) broken.keys.firstOrNull()?.let(broken::remove)
    }

    private fun tooManyBroken(from: String): Boolean = synchronized(broken) {
        val q = broken[from] ?: return false
        val now = System.currentTimeMillis()
        while (q.isNotEmpty() && q.first() < now - 10 * 60_000L) q.removeFirst()
        q.size >= 20
    }

    // ---- State ---------------------------------------------------------------------------

    /** Everything the panel shows, with no password, key, passphrase or file text in it. */
    private fun state(device: Device): JSONObject {
        val s = Control.state.value
        val status = VpnController.status.value
        val paused = VpnController.pausedUntil.value
        val t = VpnController.tunnel.value
        val liveId = status.connectionId
        val tunnel = JSONObject()
            .put("status", when {
                paused != null -> "paused"
                status is VpnStatus.Connected -> "connected"
                status is VpnStatus.Connecting -> "connecting"
                status is VpnStatus.Blocked -> "blocked"
                status is VpnStatus.Failed -> "failed"
                else -> "disconnected"
            })
            .put("profile", liveId ?: JSONObject.NULL)
            .put("since", (status as? VpnStatus.Connected)?.sinceMillis ?: JSONObject.NULL)
            .put("pausedUntil", paused ?: JSONObject.NULL)
            .put("error", (status as? VpnStatus.Failed)?.message ?: JSONObject.NULL)
            .put("advice", (status as? VpnStatus.Failed)?.message?.let(Words::whatToCheck) ?: JSONObject.NULL)
            .put("ip", t?.publicIp ?: JSONObject.NULL)
            .put("address", t?.address ?: JSONObject.NULL)
            .put("dns", JSONArray(t?.dns.orEmpty()))
            .put("remote", t?.remote ?: JSONObject.NULL)
        val profiles = JSONArray()
        s.connections.forEach { c -> profiles.put(profile(s, c, status, paused)) }
        val devices = JSONArray(Devices.list.value.map { d ->
            JSONObject().put("id", d.id).put("name", d.name).put("paired", d.paired).put("seen", maxOf(d.seen, Devices.lastSeen(d.id))).put("you", d.id == device.id)
        })
        val pinLocks = s.settings.pinMode != "Off" && s.hasPin
        return JSONObject()
            .put("v", synchronized(lock) { view })
            .put("schema", Schema.ID)
            .put("tv", JSONObject().put("name", tvName()).put("app", APP_NAME).put("version", BuildConfig.VERSION_NAME).put("build", BuildConfig.VERSION_CODE))
            .put("tunnel", tunnel)
            .put("active", s.activeId ?: JSONObject.NULL)
            .put("profiles", profiles)
            .put("settings", Schema.settingsJson(s))
            .put("lock", JSONObject().put("pin", s.hasPin).put("locks", pinLocks).put("mode", s.settings.pinMode))
            .put("backup", JSONObject().put("passphrase", s.hasBackupPassphrase).put("last", Words.lastBackup(s.lastBackup)))
            .put("you", JSONObject().put("device", device.id).put("name", device.name))
            .put("devices", devices)
            .put("vpnAllowed", VpnService.prepare(app) == null)
            .put("wireguard", dev.shieldvpn.Features.WIREGUARD)
    }

    private fun profile(s: CoreState, c: dev.shieldvpn.vpn.Connection, status: VpnStatus, paused: Long?): JSONObject {
        val server = s.servers[c.serverId]
        val info = s.infos[c.serverId]
        val kind = server?.kind ?: VpnKind.OpenVpn
        val login = s.logins[c.id]
        val mine = status.connectionId == c.id
        val live = when {
            !mine -> null
            paused != null -> ProfileStatus.Live.Paused
            status is VpnStatus.Connected -> ProfileStatus.Live.Connected
            status is VpnStatus.Connecting -> ProfileStatus.Live.Connecting
            status is VpnStatus.Blocked -> ProfileStatus.Live.Blocked
            status is VpnStatus.Failed -> ProfileStatus.Live.Failed(status.message)
            else -> null
        }
        val asks = info?.needsLogin == true && login?.hasPassword != true
        val ps = ProfileStatus.of(c, live, asks)
        val sign = Words.signIn(info, login, c.keyAlias)
        val values = JSONObject()
        val shown = JSONArray()
        Schema.profileItems.filter { it.shows(s, c) }.forEach { item ->
            shown.put(item.path)
            values.put(item.path, Schema.json(Schema.profileValue(s, c, item.path)))
        }
        return JSONObject()
            .put("id", c.id)
            .put("name", c.name)
            .put("kind", if (kind == VpnKind.WireGuard) "wireguard" else "openvpn")
            .put("kindLabel", Words.kind(kind))
            .put("server", Words.serverLine(server?.host.orEmpty(), server?.port, server?.protocol?.takeIf { kind == VpnKind.OpenVpn }))
            .put("signIn", JSONObject().put("summary", sign.summary).put("method", sign.method).put("needs", info?.needsLogin == true))
            .put("publicKey", login?.publicKey ?: JSONObject.NULL)
            .put("status", JSONObject().put("word", ps.word).put("detail", ps.detail ?: JSONObject.NULL).put("tone", ps.tone.name.lowercase()).put("line", ps.line))
            .put("lastUsed", c.lastUsed ?: JSONObject.NULL)
            .put("startup", s.settings.boot && s.settings.startupId == c.id)
            .put("items", shown)
            .put("values", values)
    }

    // ---- Events --------------------------------------------------------------------------

    /**
     * Waits until something the page shows has changed, or a while has passed, then answers with
     * the new state (when its view is old), new log lines, the tunnel's byte counts and progress.
     */
    private fun events(device: Device, req: JSONObject): JSONObject {
        val after = req.optLong("after", -1)
        val seen = req.optLong("v", -1)
        val log = req.optJSONArray("log")
        val epoch = log?.optInt(0, -1) ?: -1
        val next = log?.optLong(1, 0) ?: 0
        val connected = VpnController.status.value is VpnStatus.Connected
        val wait = if (connected) LIVE_WAIT_MS else WAIT_MS
        val end = System.currentTimeMillis() + wait
        synchronized(lock) {
            fun news() = view != seen || (after >= 0 && seq > after) || epoch != logEpoch || logStart + logLines.size > next
            while (!news() && HttpServer.running) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
        }
        val out = JSONObject().put("ok", true)
        val (v, sq, evs, lines) = synchronized(lock) {
            val evs = if (after < 0) emptyList() else events.filter { it.first > after }.map { it.second }
            val lines = if (epoch == logEpoch) {
                val from = (next - logStart).coerceAtLeast(0).toInt()
                JSONObject().put("epoch", logEpoch).put("next", logStart + logLines.size).put("reset", false)
                    .put("lines", JSONArray(logLines.drop(from).map(::lineJson)))
            } else {
                JSONObject().put("epoch", logEpoch).put("next", logStart + logLines.size).put("reset", true).put("lines", JSONArray(logLines.map(::lineJson)))
            }
            Quad(view, seq, evs, lines)
        }
        out.put("v", v).put("seq", sq).put("events", JSONArray(evs)).put("log", lines)
        if (v != seen) out.put("state", onMain { state(device) })
        VpnController.stats?.invoke()?.let { b -> out.put("bytes", JSONArray(listOf(b[0], b[1]))) }
        return out
    }

    private data class Quad(val v: Long, val seq: Long, val events: List<JSONObject>, val log: JSONObject)

    private fun lineJson(l: LogLine) = JSONArray(listOf(l.time, l.text, l.kind.name.lowercase()))

    // ---- Actions -------------------------------------------------------------------------

    private fun act(device: Device, req: JSONObject): JSONObject {
        val action = req.optString("action")
        val ok = JSONObject().put("ok", true)
        return when (action) {
            "connect" -> {
                val id = req.optString("profile")
                if (VpnService.prepare(app) != null) return fail("Android asks once before $APP_NAME can connect. Open $APP_NAME on the TV and connect there the first time.")
                val l = req.optJSONObject("login")
                val login = l?.let { CredentialStore.Credentials(it.optString("username").trim(), it.optString("password")) }
                if (login != null && (login.username.isEmpty() || login.password.isEmpty())) return fail("Type the username and password.")
                // Saving a password is a change: while the PIN locks them, the login is used this once.
                val remember = (l?.optBoolean("remember", true) ?: true) && !pinLocks()
                val started = onMain {
                    if (Control.connection(id) == null) null else Control.connect(id, login, remember)
                } ?: return fail("That profile is gone.")
                if (!started) return JSONObject().put("ok", false).put("needs", "login").put("text", "Type the username and password for ${Control.connection(id)?.name ?: "it"}.")
                if (login != null && remember) onMain { Control.note("${device.name} saved the password of ${Control.connection(id)?.name}") }
                ok
            }
            "disconnect" -> onMain { Control.disconnect(); ok }
            "pause" -> onMain { Control.pause(); ok }
            "pauseMore" -> onMain { Control.pauseMore(); ok }
            "resume" -> onMain { Control.resume(); ok }
            // What phones changed stays traceable: the cleared log starts with who cleared it.
            "clearLog" -> onMain {
                Control.clearLog()
                main.postDelayed({ Control.note("${device.name} cleared the log") }, 300)
                ok
            }
            "test" -> {
                val connected = VpnController.status.value is VpnStatus.Connected
                val checks = if (!connected) {
                    listOf(ConnectionTest.tunnel(false, null))
                } else {
                    runBlocking { listOf(ConnectionTest.tunnel(true, VpnController.tunnel.value), ConnectionTest.dns(VpnController.tunnel.value), ConnectionTest.internet()) }
                }
                ok.put("checks", checksJson(checks)).put("ipv6", Control.state.value.settings.blockIpv6)
            }
            else -> fail("The TV doesn’t know that action.")
        }
    }

    private fun checksJson(checks: List<ConnectionTest.Check>) = JSONArray(checks.map { JSONObject().put("title", it.title).put("result", it.result.name.lowercase()).put("detail", it.detail) })

    private fun fail(text: String) = JSONObject().put("ok", false).put("text", text)

    // ---- Changes -------------------------------------------------------------------------

    private fun apply(device: Device, id: String, test: Boolean, pin: String?): JSONObject {
        val p = onMain { Changes.take(id) } ?: return fail("This change has run out, or it was applied already. Check it again.")
        synchronized(lock) { remember(JSONObject().put("t", "change").put("change", id).put("state", "started")) }
        applying.incrementAndGet()
        applier.execute {
            try {
                applyNow(device, id, p, test, pin)
            } catch (t: Throwable) {
                // Nothing an apply does may end the app's process: the phone hears what went wrong.
                push(JSONObject().put("t", "change").put("change", id).put("state", "failed").put("text", "It stopped part way: ${t.message ?: "something went wrong"}."))
            } finally {
                applying.decrementAndGet()
            }
        }
        return JSONObject().put("ok", true).put("state", "started")
    }

    /** The PIN locks profiles and settings now. */
    private fun pinLocks(): Boolean = Control.state.value.let { it.settings.pinMode != "Off" && it.hasPin }

    private fun applyNow(device: Device, id: String, p: Changes.Preview, test: Boolean, pin: String?) {
        fun progress(state: String, extra: JSONObject.() -> Unit = {}) = push(JSONObject().put("t", "change").put("change", id).put("state", state).apply(extra))
        val since = onMain { Changes.changedSince(p) }
        if (since.isNotEmpty()) {
            return progress("conflict") {
                put("text", "The TV changed this meanwhile.")
                put("conflicts", JSONArray(since.map { JSONObject().put("label", it.label).put("phone", it.phone).put("tv", it.tv) }))
            }
        }
        // As the TV is locked now, not as it was when the change was checked.
        if (onMain { pinLocks() }) {
            val bad = PinTries.attempt(pin.orEmpty(), Control::checkPin)
            if (bad != null) {
                onMain { Changes.putBack(p) }
                return progress("pin") { put("text", bad) }
            }
        }
        if (p.ask.isNotEmpty()) {
            progress("asking") { put("step", "Press Allow on the TV") }
            val ask = Asks.ask(
                "From ${device.name}", "${device.name} wants to ${Words.and(p.ask)}.",
                "Allow it only if you are making this change on the phone yourself.", "Allow", danger = true,
            )
            when (Asks.await(ask, ASK_MS)) {
                true -> Unit
                false -> return progress("refused") { put("text", "Not allowed: the TV said Not now.") }
                null -> {
                    onMain { Changes.putBack(p) }
                    return progress("refused") {
                        put("text", if (Asks.screensInFront) "Nobody answered on the TV." else "Open $APP_NAME on the TV to allow this, then apply again.")
                        // The change is kept: Apply works again once the TV can answer.
                        put("retry", true)
                    }
                }
            }
        }
        val testId = p.testProfile.takeIf { test }
        if (testId != null) {
            progress("testing") { put("step", "Applying") }
            // No time limit here: the test has its own, the Connection timeout and the checks'.
            val outcome = onMain(limitMs = null) { Verify.run(testId, { Changes.run(p) }) { step -> progress("testing") { put("step", step) } } }
            when (outcome) {
                is Verify.Outcome.Passed -> {
                    onMain { Changes.logLines(device.name, p).forEach(Control::note) }
                    progress("applied") {
                        put("checks", checksJson(outcome.checks))
                        outcome.added?.let { put("profile", it) }
                    }
                }
                is Verify.Outcome.RolledBack -> {
                    onMain { Control.note("${device.name}’s change was tested and rolled back: ${outcome.reason}") }
                    progress("rolledBack") {
                        put("text", outcome.reason)
                        put("checks", checksJson(outcome.checks))
                    }
                }
            }
        } else {
            progress("applying")
            val (added, problem) = onMain {
                val r = Changes.run(p)
                if (r.second == null) {
                    Changes.logLines(device.name, p).forEach(Control::note)
                    if (p.effect == Effect.Reconnect) Control.reconnect()
                }
                r
            }
            if (problem != null) progress("failed") { put("text", problem) } else progress("applied") { added?.let { put("profile", it) } }
        }
    }

    // ---- Files ---------------------------------------------------------------------------

    private fun file(req: JSONObject): JSONObject {
        val bytes = Seal.unb64(req.optString("data")) ?: return fail("The TV could not read that file.")
        if (bytes.size > MAX_FILE) return fail("That file is too large. A profile, backup or certificate is much smaller.")
        val name = req.optString("name").take(120).ifBlank { "pasted.ovpn" }
        return onMain {
            val u = Changes.upload(name, bytes)
            val out = JSONObject().put("ok", true).put("file", u.id).put("name", name)
            when (val w = u.what) {
                is Inspected.Problem -> out.put("ok", false).put("what", "problem").put("text", w.message)
                Inspected.Backup -> out.put("what", "backup")
                Inspected.Pkcs12 -> out.put("what", "p12").put("targets", JSONArray(Control.state.value.let { s ->
                    s.connections.filter { s.servers[it.serverId]?.kind != VpnKind.WireGuard }.map { JSONObject().put("id", it.id).put("name", it.name) }
                }))
                is Inspected.Profile -> {
                    out.put("what", "profile").put("review", review(w.review))
                    Control.sameServer(w.review)?.let { out.put("same", JSONObject().put("id", it.id).put("name", it.name)) }
                }
            }
            out
        }
    }

    private fun review(r: ImportReview): JSONObject = JSONObject()
        .put("name", r.name)
        .put("fileName", if (r.fileName == "pasted.ovpn") "pasted text" else r.fileName)
        .put("kind", if (r.kind == VpnKind.WireGuard) "wireguard" else "openvpn")
        .put("kindLabel", Words.kind(r.kind))
        .put("server", Words.serverLine(r.host, r.port, r.protocol.takeIf { r.kind == VpnKind.OpenVpn }))
        .put("needsLogin", r.needsLogin)
        .put("username", r.username)
        .put("hasPassword", r.password.isNotEmpty())
        .put("canSignIn", r.kind == VpnKind.WireGuard || r.needsLogin || ServerStore.hasClientCertificate(r.text))
        .put("warnings", JSONArray(r.warnings))
        .put("note", r.note ?: JSONObject.NULL)
        // What the server's peer list needs, shown before the profile is even saved.
        .put("publicKey", r.wgSecrets?.let { k -> runCatching { com.wireguard.crypto.KeyPair(com.wireguard.crypto.Key.fromBase64(k.privateKey)).publicKey.toBase64() }.getOrNull() } ?: JSONObject.NULL)

    // The phones that have a question waiting on the TV: one each, so none can fill the server's threads.
    private val asking = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Asks the TV for [device], one question at a time: its answer, or null when there was none. */
    private fun askTv(device: Device, ask: Asks.Ask): Boolean? {
        if (!asking.add(device.id)) return null
        return try {
            Asks.await(ask, ASK_MS)
        } finally {
            asking.remove(device.id)
        }
    }

    /** Null when the PIN is right or not needed, or what to say. */
    private fun pinProblem(req: JSONObject): String? =
        if (!onMain { pinLocks() }) null else PinTries.attempt(req.optString("pin"), Control::checkPin)

    /** A file with keys in it leaves the TV only after the remote's OK. */
    private fun download(device: Device, req: JSONObject): JSONObject {
        val what = req.optString("what")
        pinProblem(req)?.let { return fail(it) }
        val (question, file) = when (what) {
            "backup" -> {
                if (!Control.state.value.hasBackupPassphrase) return fail("Choose a backup passphrase first, in Settings › Backup.")
                "download a backup of every profile and saved password" to suspend {
                    Control.backupFile()?.let { "$APP_FILE_NAME-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())}.${dev.shieldvpn.vpn.Backup.EXTENSION}" to it }
                }
            }
            "profile" -> {
                val c = Control.connection(req.optString("profile")) ?: return fail("That profile is gone.")
                val kind = Control.state.value.servers[c.serverId]?.kind
                "download ${c.name}’s file, with its ${if (kind == VpnKind.WireGuard) "private key" else "keys"}" to suspend {
                    Control.profileFile(c.id)?.let { "${c.name.replace(Regex("[^A-Za-z0-9 ._-]"), "_")}.${if (kind == VpnKind.WireGuard) "conf" else "ovpn"}" to it }
                }
            }
            else -> return fail("The TV doesn’t know that file.")
        }
        if (device.id in asking) return fail("The TV is already asking something from this phone. Answer that first.")
        val ask = Asks.ask("From ${device.name}", "${device.name} wants to $question.", "The file goes to the phone. Allow it only if you asked for it there.", "Allow", danger = true)
        when (askTv(device, ask)) {
            true -> Unit
            false -> return fail("Not allowed: the TV said Not now.")
            null -> return fail(if (Asks.screensInFront) "Nobody answered on the TV." else "Open $APP_NAME on the TV to allow this, then try again.")
        }
        val (name, bytes) = onMain { file() } ?: return fail("The TV couldn’t make that file.")
        if (what == "backup") onMain { Control.backupDownloaded() }
        onMain { Control.note("${device.name} downloaded $name") }
        return JSONObject().put("ok", true).put("name", name).put("data", Seal.b64(bytes))
    }

    private fun removeDevice(device: Device, id: String, req: JSONObject): JSONObject {
        val other = Devices.list.value.firstOrNull { it.id == id } ?: return fail("That phone isn’t paired any more.")
        if (other.id != device.id) {
            pinProblem(req)?.let { return fail(it) }
            if (device.id in asking) return fail("The TV is already asking something from this phone. Answer that first.")
            val ask = Asks.ask("From ${device.name}", "${device.name} wants to remove ${other.name}.", "${other.name} then can’t reach the TV until it pairs again.", "Remove", danger = true)
            if (askTv(device, ask) != true) return fail("Not removed: allow it on the TV.")
        }
        Devices.remove(other.id)
        onMain {
            Control.note("${device.name} removed the phone ${other.name}")
            // With no phone left, nothing needs to listen while the VPN is on.
            Control.remoteChanged()
        }
        return JSONObject().put("ok", true)
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /** The launcher's apps, for Settings › Apps on the panel; read once. */
    private fun launcherApps() {
        if (Schema.launcherApps.isNotEmpty()) return
        val pm = app.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tv = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val phone = runCatching { pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0) }.getOrDefault(emptyList())
        Schema.launcherApps = (tv + phone).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != app.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
            .map { (pkg, label) -> Option(pkg, label, null) }
    }

    private class Box<T>(val value: T)

    /** Runs [block] on the main thread, where [Control] lives, and waits for it, up to [limitMs]. */
    private fun <T> onMain(limitMs: Long? = 120_000, block: suspend () -> T): T = runBlocking {
        if (limitMs == null) return@runBlocking withContext(Dispatchers.Main) { block() }
        (withTimeoutOrNull(limitMs) { Box(withContext(Dispatchers.Main) { block() }) } ?: error("The TV took too long to answer")).value
    }
}
