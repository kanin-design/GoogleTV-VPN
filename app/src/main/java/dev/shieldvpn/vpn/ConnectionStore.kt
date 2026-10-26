package dev.shieldvpn.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Profiles (connections) live in filesDir/connections.json, in the order the list shows
 * them. Their logins are kept apart, encrypted, in [CredentialStore]. The VPN's process writes
 * here too (when a profile last connected or failed), so each change holds a file lock across
 * both processes, and the file is replaced whole ([writeAtomically]), never read half-written.
 */
object ConnectionStore {

    private const val FILE = "connections.json"

    // The last list read, for a file that later turns out damaged.
    private var lastGood: List<Connection>? = null

    @Synchronized
    fun list(context: Context): List<Connection> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return migrate(context)
        return runCatching { parse(readAtomically(file).orEmpty()) }
            .onSuccess { lastGood = it }
            .getOrElse { setAside(file, it); lastGood ?: emptyList() }
    }

    private fun parse(text: String): List<Connection> {
        val array = JSONArray(text)
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            Connection(
                id = item.getString("id"),
                name = item.getString("name"),
                serverId = item.getString("serverId"),
                lastError = item.optStringOrNull("lastError"),
                pinned = item.optBoolean("pinned", false),
                lastUsed = if (item.has("lastUsed")) item.getLong("lastUsed") else null,
                killMode = item.optStringOrNull("killMode") ?: Connection.APP_SETTING,
                dnsMode = item.optStringOrNull("dnsMode") ?: Connection.APP_SETTING,
                order = item.optStringOrNull("order") ?: "In order",
                lookIcon = item.optStringOrNull("lookIcon"),
                lookColour = if (item.has("lookColour")) item.getInt("lookColour") else null,
                keyAlias = item.optStringOrNull("keyAlias"),
            )
        }
    }

    @Synchronized
    fun add(context: Context, name: String, serverId: String): Connection = locked(context) {
        val connection = Connection(UUID.randomUUID().toString(), name, serverId)
        write(context, list(context) + connection)
        connection
    }

    /** Puts a copy right after the original. */
    @Synchronized
    fun insertAfter(context: Context, afterId: String, copy: Connection) = locked(context) {
        val all = list(context)
        val at = all.indexOfFirst { it.id == afterId }
        write(context, if (at < 0) all + copy else all.take(at + 1) + copy + all.drop(at + 1))
    }

    @Synchronized
    fun update(context: Context, id: String, transform: (Connection) -> Connection) = locked(context) {
        write(context, list(context).map { if (it.id == id) transform(it) else it })
    }

    @Synchronized
    fun delete(context: Context, id: String) = locked(context) { write(context, list(context).filterNot { it.id == id }) }

    @Synchronized
    fun clear(context: Context) = locked(context) { write(context, emptyList()) }

    /** Holds the lock file while [block] reads and rewrites the list; the other process waits. */
    private inline fun <T> locked(context: Context, block: () -> T): T =
        RandomAccessFile(File(context.filesDir, "$FILE.lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }

    private fun write(context: Context, connections: List<Connection>) {
        val array = JSONArray()
        connections.forEach { c ->
            val item = JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("serverId", c.serverId)
                .put("pinned", c.pinned)
                .put("killMode", c.killMode)
                .put("dnsMode", c.dnsMode)
                .put("order", c.order)
            c.lastError?.let { item.put("lastError", it) }
            c.lastUsed?.let { item.put("lastUsed", it) }
            c.lookIcon?.let { item.put("lookIcon", it) }
            c.lookColour?.let { item.put("lookColour", it) }
            c.keyAlias?.let { item.put("keyAlias", it) }
            array.put(item)
        }
        writeAtomically(File(context.filesDir, FILE), array.toString())
        lastGood = connections
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) getString(key) else null

    /**
     * Earlier builds kept one .ovpn per server in filesDir/profiles, with display names
     * in profiles/index.json and logins keyed by the file's name. Each file becomes a
     * server and a connection with that same id, so its login carries over unchanged.
     */
    private fun migrate(context: Context): List<Connection> {
        val old = File(context.filesDir, "profiles")
        val names = oldNames(File(old, "index.json"))
        val order = names.keys.toList()
        val files = old.listFiles().orEmpty()
            .filter { it.extension == "ovpn" }
            .sortedWith(compareBy({ order.indexOf(it.nameWithoutExtension).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))
        val connections = files.map { file ->
            val id = file.nameWithoutExtension
            ServerStore.add(context, file.readText(), id)
            Connection(id, names[id] ?: ServerStore.nameFromFile(file.name), id)
        }
        write(context, connections)
        // The very first build kept one login for the whole app.
        connections.firstOrNull()?.let { CredentialStore.adoptLegacy(context, it.id) }
        old.deleteRecursively()
        return connections
    }

    private fun oldNames(index: File): Map<String, String> {
        if (!index.exists()) return emptyMap()
        val array = runCatching { JSONArray(index.readText()) }.getOrNull() ?: return emptyMap()
        return (0 until array.length()).associate { i ->
            val item = array.getJSONObject(i)
            item.getString("id") to item.getString("name")
        }
    }
}
