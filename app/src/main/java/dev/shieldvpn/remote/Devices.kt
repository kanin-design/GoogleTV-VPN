package dev.shieldvpn.remote

import android.content.Context
import dev.shieldvpn.vpn.CredentialStore
import dev.shieldvpn.vpn.readAtomically
import dev.shieldvpn.vpn.setAside
import dev.shieldvpn.vpn.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A paired phone, as Settings › Phone access lists it. Its key never leaves [Devices]. */
data class Device(val id: String, val name: String, val paired: Long, val seen: Long)

/**
 * The paired phones: remote/devices.json holds their names and times, and each key is kept
 * encrypted with the saved passwords. A phone unused for 90 days is forgotten.
 *
 * Counters are kept in memory. The file holds a reserve above the highest one used, raised in
 * steps, so after a restart nothing at or under it is taken: a request heard before then can't
 * be sent again, and the phone is told where to go on from.
 */
object Devices {
    private const val FILE = "remote/devices.json"
    private const val SECRET = "device."
    private const val UNUSED_MS = 90L * 24 * 60 * 60 * 1000
    private const val RESERVE = 1_000L
    private const val SEEN_EVERY_MS = 10 * 60_000L

    private class Live(var device: Device, val keys: Seal.Keys, val window: Seal.Window, var reserved: Long) {
        /** The last request, which the file and the list get only every ten minutes. */
        @Volatile var lastRequest = device.seen
    }

    private lateinit var app: Context
    private var loaded = false
    private val live = LinkedHashMap<String, Live>()

    private val _list = MutableStateFlow<List<Device>>(emptyList())
    val list: StateFlow<List<Device>> = _list.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        synchronized(this) { load() }
    }

    /** Whether any phone is paired, from either process: the VPN's reads only the file. */
    fun any(context: Context): Boolean =
        readAtomically(File(context.filesDir, FILE))?.let { runCatching { JSONObject(it).getJSONArray("devices").length() > 0 }.getOrNull() } == true

    /** Pairs a phone called [name]; returns its id and the key to hand it, once. */
    fun add(name: String): Pair<Device, ByteArray> = synchronized(this) {
        load()
        val id = "d_" + Seal.b64url(Seal.randomBytes(6))
        val key = Seal.randomBytes(Seal.KEY_BYTES)
        val now = System.currentTimeMillis()
        val device = Device(id, name.trim().take(40).ifEmpty { "Phone" }, now, now)
        CredentialStore.putSecret(app, SECRET + id, Seal.b64(key))
        live[id] = Live(device, Seal.Keys.of(key), Seal.Window(0, 0), RESERVE)
        save()
        device to key
    }

    fun remove(id: String) = synchronized(this) {
        load()
        if (live.remove(id) == null) return@synchronized
        CredentialStore.putSecret(app, SECRET + id, null)
        save()
    }

    sealed interface Opened {
        data class Ok(val device: Device, val plain: ByteArray) : Opened
        /** No such phone: never paired, removed on the TV, or unused for too long. */
        data object Unknown : Opened
        /** The counter was used already, or is too old; [next] is where the phone goes on from. */
        data class Old(val next: Long) : Opened
        /** The seal didn't open: another key, a changed byte, or another request's body. */
        data object Broken : Opened
    }

    /** Opens what phone [id] sent with counter [n], from the page's [lane], for the request [aad]. */
    fun open(id: String, n: Long, lane: Int, aad: ByteArray, sealed: ByteArray): Opened {
        val l = synchronized(this) {
            load()
            live[id]?.takeIf { System.currentTimeMillis() - it.device.seen < UNUSED_MS } ?: return Opened.Unknown
        }
        synchronized(l) {
            if (!l.window.fresh(n)) return Opened.Old(l.window.top + 1)
            val plain = Seal.open(l.keys.up, Seal.UP, n, aad, sealed, lane) ?: return Opened.Broken
            l.window.mark(n)
            val now = System.currentTimeMillis()
            l.lastRequest = now
            var write = false
            if (n >= l.reserved - RESERVE / 4) {
                l.reserved = n + RESERVE
                write = true
            }
            if (now - l.device.seen > SEEN_EVERY_MS) {
                l.device = l.device.copy(seen = now)
                write = true
            }
            if (write) synchronized(this) { save() }
            return Opened.Ok(l.device, plain)
        }
    }

    /** When phone [id] last asked anything, to the second; [Device.seen] moves only every ten minutes. */
    fun lastSeen(id: String): Long = synchronized(this) { live[id]?.lastRequest } ?: 0L

    /** Seals the answer to phone [id]'s request [n]. */
    fun seal(id: String, n: Long, lane: Int, aad: ByteArray, plain: ByteArray): ByteArray? {
        val l = synchronized(this) { live[id] } ?: return null
        return Seal.seal(l.keys.down, Seal.DOWN, n, aad, plain, lane)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val file = File(app.filesDir, FILE)
        val text = readAtomically(file) ?: return publishList()
        val now = System.currentTimeMillis()
        runCatching {
            val a = JSONObject(text).getJSONArray("devices")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val id = o.getString("id")
                val d = Device(id, o.getString("name"), o.getLong("paired"), o.getLong("seen"))
                val key = CredentialStore.secret(app, SECRET + id)?.let(Seal::unb64)
                if (key == null || key.size != Seal.KEY_BYTES || now - d.seen > UNUSED_MS) {
                    CredentialStore.putSecret(app, SECRET + id, null)
                    continue
                }
                val reserved = o.optLong("reserved", RESERVE)
                live[id] = Live(d, Seal.Keys.of(key), Seal.Window(reserved), reserved + RESERVE)
            }
        }.onFailure { setAside(file, it) }
        // Forgotten ones are written out, and the reserves move up past this start.
        save()
    }

    private fun save() {
        val a = JSONArray()
        live.values.forEach { l ->
            a.put(JSONObject().put("id", l.device.id).put("name", l.device.name).put("paired", l.device.paired).put("seen", l.device.seen).put("reserved", l.reserved))
        }
        val file = File(app.filesDir, FILE)
        file.parentFile?.mkdirs()
        runCatching { writeAtomically(file, JSONObject().put("devices", a).toString()) }
        publishList()
    }

    private fun publishList() {
        _list.value = live.values.map { it.device }
    }
}
