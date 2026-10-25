package dev.shieldvpn.vpn

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/** Settings that must survive an app restart. */
object SettingsStore {

    private const val PREFS = "shieldvpn_settings"

    fun load(context: Context): AppSettings {
        val p = prefs(context)
        val d = AppSettings()
        return AppSettings(
            killSwitch = p.getBoolean("killSwitch", d.killSwitch),
            boot = p.getBoolean("connectAtStartup", d.boot),
            startupId = p.getString("startupId", null),
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
        )
    }

    fun save(context: Context, s: AppSettings) {
        prefs(context).edit()
            .putBoolean("killSwitch", s.killSwitch)
            .putBoolean("connectAtStartup", s.boot)
            .putString("startupId", s.startupId)
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
            .apply()
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
