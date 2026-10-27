package dev.shieldvpn.vpn

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/** Settings that must survive an app restart. */
object SettingsStore {

    const val PREFS = "shieldvpn_settings"

    fun load(context: Context): AppSettings {
        val p = prefs(context)
        val d = AppSettings()
        return AppSettings(
            killSwitch = p.getBoolean("killSwitch", d.killSwitch),
            boot = p.getBoolean("connectAtStartup", d.boot),
            startupId = p.getString("startupId", null),
            startupBefore = p.getString("startupBefore", null),
            allowLan = p.getBoolean("allowLan", d.allowLan),
            blockIpv6 = p.getBoolean("blockIpv6", d.blockIpv6),
            advanced = p.getBoolean("advanced", d.advanced),
            pinMode = p.getString("pinMode", d.pinMode) ?: d.pinMode,
            roam = p.getBoolean("roam", d.roam),
            stayAwake = p.getBoolean("stayAwake", d.stayAwake),
            blockDns = p.getBoolean("blockDns", d.blockDns),
            appMode = p.getString("appMode", d.appMode) ?: d.appMode,
            apps = p.getStringSet("apps", null)?.toSet() ?: d.apps,
            proto = p.getString("proto", d.proto) ?: d.proto,
            timeout = p.getInt("timeout", d.timeout),
            mtu = p.getString("mtu", d.mtu) ?: d.mtu,
            tlsMin = p.getString("tlsMin", d.tlsMin) ?: d.tlsMin,
            legacyCiphers = p.getBoolean("legacyCiphers", d.legacyCiphers),
            compress = p.getBoolean("compress", d.compress),
            verb = p.getString("verb", d.verb) ?: d.verb,
            pushedDns = p.getBoolean("pushedDns", d.pushedDns),
            customDns = p.getString("customDns", "")?.split(' ')?.filter { it.isNotBlank() } ?: emptyList(),
            searchDomains = p.getString("searchDomains", "")?.split(' ')?.filter { it.isNotBlank() } ?: emptyList(),
            automation = p.getBoolean("automation", d.automation),
            autoUpdate = p.getBoolean("autoUpdate", d.autoUpdate),
            backupPassword = p.getBoolean("backupPassword", d.backupPassword),
            phoneReach = p.getString("phoneReach", d.phoneReach) ?: d.phoneReach,
        )
    }

    fun save(context: Context, s: AppSettings) {
        prefs(context).edit()
            .putBoolean("killSwitch", s.killSwitch)
            .putBoolean("connectAtStartup", s.boot)
            .putString("startupId", s.startupId)
            .putString("startupBefore", s.startupBefore)
            .remove("backdrop")
            .putBoolean("allowLan", s.allowLan)
            .putBoolean("blockIpv6", s.blockIpv6)
            .putBoolean("advanced", s.advanced)
            .putString("pinMode", s.pinMode)
            .putBoolean("roam", s.roam)
            .putBoolean("stayAwake", s.stayAwake)
            .putBoolean("blockDns", s.blockDns)
            .putString("appMode", s.appMode)
            .putStringSet("apps", s.apps)
            .putString("proto", s.proto)
            .putInt("timeout", s.timeout)
            .putString("mtu", s.mtu)
            .putString("tlsMin", s.tlsMin)
            .putBoolean("legacyCiphers", s.legacyCiphers)
            .putBoolean("compress", s.compress)
            .putString("verb", s.verb)
            .putBoolean("pushedDns", s.pushedDns)
            .putString("customDns", s.customDns.joinToString(" "))
            .putString("searchDomains", s.searchDomains.joinToString(" "))
            .putBoolean("automation", s.automation)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("backupPassword", s.backupPassword)
            .putString("phoneReach", s.phoneReach)
            .apply()
        syncReach(context, s.phoneReach)
    }

    private const val REACH_FILE = "phone-reach"

    /**
     * "When phones can reach the TV", kept in a file of its own as well: the VPN's process reads it
     * there each time, as preferences are cached per process and never see the app's writes.
     */
    fun phoneReach(context: Context): String =
        readAtomically(File(context.filesDir, REACH_FILE))?.trim()?.ifEmpty { null } ?: AppSettings().phoneReach

    /** Writes the file when it differs; the app's process only. */
    fun syncReach(context: Context, reach: String) {
        val file = File(context.filesDir, REACH_FILE)
        if (readAtomically(file)?.trim() != reach) runCatching { writeAtomically(file, reach) }
    }

    /**
     * The profile whose tunnel an update from Settings › About ends, to connect again once the new
     * build is in. Written at once (commit), because the installer ends this process right after.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    fun setReconnectAfterUpdate(context: Context, id: String?) {
        val edit = prefs(context).edit()
        if (id == null) edit.remove("reconnectAfterUpdate") else edit.putString("reconnectAfterUpdate", "$id ${System.currentTimeMillis()}")
        edit.commit()
    }

    /** Takes that profile, if the update was in the last ten minutes. */
    fun takeReconnectAfterUpdate(context: Context): String? {
        val saved = prefs(context).getString("reconnectAfterUpdate", null) ?: return null
        setReconnectAfterUpdate(context, null)
        val at = saved.substringAfterLast(' ').toLongOrNull() ?: return null
        return saved.substringBeforeLast(' ').takeIf { System.currentTimeMillis() - at < 10 * 60 * 1000L }
    }

    /** When a phone last downloaded a backup, and how many profiles were in it. */
    fun lastBackup(context: Context): Pair<Long, Int>? {
        val at = prefs(context).getLong("lastBackupAt", 0L).takeIf { it > 0 } ?: return null
        return at to prefs(context).getInt("lastBackupProfiles", 0)
    }

    fun setLastBackup(context: Context, at: Long, profiles: Int) {
        prefs(context).edit().putLong("lastBackupAt", at).putInt("lastBackupProfiles", profiles).apply()
    }

    /** The profile Connect shows. */
    fun activeConnection(context: Context): String? = prefs(context).getString("activeConnection", null)

    fun setActiveConnection(context: Context, id: String?) {
        prefs(context).edit().putString("activeConnection", id).apply()
    }

    // ---- The PIN, kept as a salted hash -----------------------------------------------

    fun hasPin(context: Context): Boolean = prefs(context).contains("pinHash")

    fun setPin(context: Context, pin: String?) {
        val p = prefs(context).edit()
        if (pin == null) {
            p.remove("pinHash").remove("pinSalt")
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            p.putString("pinSalt", salt.toHex()).putString("pinHash", hash(salt, pin))
        }
        p.apply()
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString("pinSalt", null)?.fromHex() ?: return false
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), p.getString("pinHash", "")!!.toByteArray())
    }

    private fun hash(salt: ByteArray, pin: String): String =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(pin.toByteArray()).toHex()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
