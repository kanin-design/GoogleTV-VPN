package dev.shieldvpn.control

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import dev.shieldvpn.vpn.ConnectionStore
import dev.shieldvpn.vpn.CredentialStore
import dev.shieldvpn.vpn.SettingsStore
import dev.shieldvpn.vpn.readAtomically
import dev.shieldvpn.vpn.writeAtomically
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The stores as they were before an Apply and test: profiles, server files, settings and the
 * saved logins (still encrypted). A failed test puts them back. The copy sits in the cache with a
 * "pending" marker, so an app killed part way through puts them back at its next start.
 */
object Snapshot {
    private const val DIR = "apply-snapshot"
    private const val CONNECTIONS = "connections.json"
    private const val SERVERS = "servers"

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    private fun marker(context: Context) = File(dir(context), "pending")

    fun take(context: Context) {
        val d = dir(context)
        d.deleteRecursively()
        File(d, SERVERS).mkdirs()
        File(context.filesDir, CONNECTIONS).takeIf { it.exists() }?.copyTo(File(d, CONNECTIONS), overwrite = true)
        File(context.filesDir, SERVERS).listFiles()?.filter { it.isFile }?.forEach { it.copyTo(File(d, "$SERVERS/${it.name}"), overwrite = true) }
        writeAtomically(File(d, "settings.json"), prefsJson(context.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE)).toString())
        writeAtomically(File(d, "logins.json"), prefsJson(context.getSharedPreferences(CredentialStore.PREFS, Context.MODE_PRIVATE), skip = PHONE_KEYS).toString())
        marker(context).writeText(System.currentTimeMillis().toString())
    }

    /** The test passed: the copy goes. */
    fun drop(context: Context) {
        dir(context).deleteRecursively()
    }

    val Context.pendingApply: Boolean get() = marker(this).exists()

    /** Puts the stores back as they were when [take] ran. Returns false when there is no copy. */
    fun restore(context: Context): Boolean {
        val d = dir(context)
        if (!marker(context).exists()) return false
        val connections = File(d, CONNECTIONS)
        ConnectionStore.replaceFile(context, if (connections.exists()) readAtomically(connections).orEmpty() else null)
        val servers = File(context.filesDir, SERVERS)
        val kept = File(d, SERVERS).listFiles()?.filter { it.isFile }.orEmpty()
        // Files added since go; the ones that were there come back as they were.
        servers.listFiles()?.filter { it.isFile && kept.none { k -> k.name == it.name } }?.forEach { it.delete() }
        kept.forEach { writeAtomically(File(servers, it.name), readAtomically(it).orEmpty()) }
        readAtomically(File(d, "settings.json"))?.let { restorePrefs(context.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE), JSONObject(it)) }
        readAtomically(File(d, "logins.json"))?.let { restorePrefs(context.getSharedPreferences(CredentialStore.PREFS, Context.MODE_PRIVATE), JSONObject(it), keep = PHONE_KEYS) }
        drop(context)
        return true
    }

    // Paired phones' keys are no part of a change: a phone paired or removed meanwhile stays so.
    private const val PHONE_KEYS = "secret.device."

    private fun prefsJson(p: SharedPreferences, skip: String? = null): JSONObject {
        val o = JSONObject()
        p.all.forEach { (k, v) ->
            if (skip != null && k.startsWith(skip)) return@forEach
            val typed = when (v) {
                is Boolean -> JSONObject().put("b", v)
                is Int -> JSONObject().put("i", v)
                is Long -> JSONObject().put("l", v)
                is Float -> JSONObject().put("f", v.toDouble())
                is String -> JSONObject().put("s", v)
                is Set<*> -> JSONObject().put("set", JSONArray(v.map { it.toString() }))
                else -> null
            }
            typed?.let { o.put(k, it) }
        }
        return o
    }

    @SuppressLint("ApplySharedPref")
    private fun restorePrefs(p: SharedPreferences, o: JSONObject, keep: String? = null) {
        val e = p.edit()
        p.all.keys.filter { keep == null || !it.startsWith(keep) }.forEach { e.remove(it) }
        o.keys().forEach { k ->
            val v = o.getJSONObject(k)
            when {
                v.has("b") -> e.putBoolean(k, v.getBoolean("b"))
                v.has("i") -> e.putInt(k, v.getInt("i"))
                v.has("l") -> e.putLong(k, v.getLong("l"))
                v.has("f") -> e.putFloat(k, v.getDouble("f").toFloat())
                v.has("s") -> e.putString(k, v.getString("s"))
                v.has("set") -> e.putStringSet(k, v.getJSONArray("set").let { a -> (0 until a.length()).map(a::getString).toSet() })
            }
        }
        // Written now: a restore at start-up is followed by reading these stores.
        e.commit()
    }
}
