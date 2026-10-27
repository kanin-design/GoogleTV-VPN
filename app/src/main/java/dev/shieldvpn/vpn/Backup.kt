package dev.shieldvpn.vpn

import android.content.Context
import android.util.Base64
import dev.shieldvpn.APP_FILE_NAME
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.Features
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A .openvpnclientbackup file: every profile with its file and login, and the settings, encrypted
 * with a passphrase (PBKDF2-SHA256, then AES-256-GCM). Only someone with the passphrase can
 * read it, so it is safe to keep on a phone. With Settings › Backup › Use a password off, the same
 * contents go in plain, under "contents", and restore without a passphrase.
 *
 * WireGuard profiles go in an array of their own, "wireguard", so older builds, which read only
 * "profiles", restore the rest and skip them. "position" keeps the order across both.
 */
object Backup {
    /** "openvpnclientbackup", from the app's name. A backup is known by what is inside, not by this. */
    val EXTENSION = "${APP_FILE_NAME}backup"

    // What a backup is known by. It never changes with the app's name, so older backups still restore.
    private const val FORMAT = "tunnel-backup"
    // For new backups, as OWASP advises for PBKDF2-SHA256; a backup keeps the count it was made with.
    private const val ITERATIONS = 600_000

    /** The backup, encrypted with [passphrase], or plain without one. */
    fun create(context: Context, passphrase: String?): ByteArray {
        val s = SettingsStore.load(context)
        val profiles = JSONArray()
        val wireguard = JSONArray()
        val kinds = ServerStore.list(context).associate { it.id to it.kind }
        ConnectionStore.list(context).forEachIndexed { position, c ->
            val config = ServerStore.effectiveConfig(context, c.serverId) ?: return@forEachIndexed
            val item = JSONObject()
                .put("name", c.name)
                .put("pinned", c.pinned)
                .put("killMode", c.killMode)
                .put("dnsMode", c.dnsMode)
                .put("lookIcon", c.lookIcon ?: JSONObject.NULL)
                .put("lookColour", c.lookColour ?: JSONObject.NULL)
                // Ids are new on the other device, so the startup profile is marked on itself.
                .put("startup", c.id == s.startupId)
                .put("position", position)
            if (kinds[c.serverId] == VpnKind.WireGuard) {
                // The data keeps WireGuard profiles whatever the build, so a backup does too.
                val keys = CredentialStore.wireGuard(context, c.id) ?: return@forEachIndexed
                wireguard.put(item.put("conf", config).put("privateKey", keys.privateKey).put("presharedKeys", JSONObject(keys.presharedKeys)))
            } else {
                val login = CredentialStore.load(context, c.id)
                profiles.put(item.put("ovpn", config).put("order", c.order).put("username", login.username).put("password", login.password))
            }
        }
        // Every setting but the PIN: the PIN is not in a backup, so neither is what it locks.
        val settings = JSONObject()
            .put("killSwitch", s.killSwitch)
            .put("allowLan", s.allowLan)
            .put("blockIpv6", s.blockIpv6)
            .put("blockDns", s.blockDns)
            .put("roam", s.roam)
            .put("appMode", s.appMode)
            .put("apps", JSONArray(s.apps.toList()))
            .put("proto", s.proto)
            .put("timeout", s.timeout)
            .put("mtu", s.mtu)
            .put("tlsMin", s.tlsMin)
            .put("pushedDns", s.pushedDns)
            .put("customDns", JSONArray(s.customDns))
            .put("searchDomains", JSONArray(s.searchDomains))
            .put("boot", s.boot)
            .put("stayAwake", s.stayAwake)
            .put("legacyCiphers", s.legacyCiphers)
            .put("compress", s.compress)
            .put("verb", s.verb)
            .put("advanced", s.advanced)
            .put("automation", s.automation)
            .put("autoUpdate", s.autoUpdate)
        val contents = JSONObject().put("format", FORMAT).put("version", 1).put("created", System.currentTimeMillis())
            .put("profiles", profiles).put("settings", settings)
            .apply { if (wireguard.length() > 0) put("wireguard", wireguard) }
        if (passphrase.isNullOrEmpty()) {
            return JSONObject().put("format", FORMAT).put("version", 1).put("contents", contents).toString(2).toByteArray()
        }
        val plain = contents.toString().toByteArray()
        val salt = random(16)
        val iv = random(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)) }
        return JSONObject()
            .put("format", FORMAT).put("version", 1).put("kdf", "pbkdf2-sha256").put("iterations", ITERATIONS)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(cipher.doFinal(plain)))
            .toString(2).toByteArray()
    }

    /** True when [bytes] look like a backup file, before the passphrase is known. */
    fun isBackup(bytes: ByteArray): Boolean = runCatching { JSONObject(String(bytes)).getString("format") == FORMAT }.getOrDefault(false)

    /** True unless [bytes] are a backup made without a password, which restores without one. */
    fun encrypted(bytes: ByteArray?): Boolean = bytes == null || runCatching { !JSONObject(String(bytes)).has("contents") }.getOrDefault(true)

    sealed interface Restored {
        /**
         * [profiles] were added; [already] were on this device already, exactly the same, so they
         * were left alone; [replaced] took the place of one with the same name.
         */
        data class Done(val profiles: Int, val already: Int = 0, val replaced: Int = 0) : Restored
        data class Problem(val message: String) : Restored
    }

    /** A backup opened with its passphrase: its plain contents and its profiles in order. */
    private sealed interface Opened {
        class Plain(val json: JSONObject, val profiles: List<JSONObject>) : Opened
        class Problem(val message: String) : Opened
    }

    private fun open(bytes: ByteArray, passphrase: String): Opened {
        val outer = runCatching { JSONObject(String(bytes)) }.getOrNull()?.takeIf { it.optString("format") == FORMAT }
            ?: return Opened.Problem("This isn’t a ${APP_NAME} backup.")
        // The file picks its own count (120 000 in older backups); a huge one would keep the TV busy for hours.
        val iterations = outer.optInt("iterations", 120_000)
        if (iterations !in 10_000..2_000_000) return Opened.Problem("This backup can’t be read.")
        val plain = outer.optJSONObject("contents") ?: runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(passphrase, unb64(outer.getString("salt")), iterations),
                GCMParameterSpec(128, unb64(outer.getString("iv"))),
            )
            JSONObject(String(cipher.doFinal(unb64(outer.getString("data")))))
        }.getOrNull() ?: return Opened.Problem("Wrong passphrase, or the file is damaged.")
        // Each profile is checked before anything is written, so a damaged one changes nothing.
        val profiles = runCatching {
            fun items(key: String, file: String) = plain.optJSONArray(key)?.let { a -> List(a.length()) { i -> a.getJSONObject(i).also { it.getString("name"); it.getString(file) } } }.orEmpty()
            val wireguard = items("wireguard", "conf").onEach { require(WireGuardFile.isKey(it.getString("privateKey"))) }
            // In the order they were on the other device; a backup without positions keeps its own.
            (items("profiles", "ovpn") + wireguard).withIndex().sortedBy { (i, p) -> p.optInt("position", i) }.map { it.value }
        }.getOrNull() ?: return Opened.Problem("The backup is damaged, so nothing was restored.")
        return Opened.Plain(plain, profiles)
    }

    /**
     * What restoring would do, before anything is written: the profiles it adds (with the names
     * they get), those it replaces with [replace] (and their ids here), those here already, and the
     * settings it brings, as the backup has them. Worked out exactly as the restore does.
     */
    class Peek(val added: List<String>, val replaced: List<String>, val replacedIds: List<String>, val same: List<String>, val settings: AppSettings?)

    sealed interface Peeked {
        class Done(val peek: Peek) : Peeked
        class Problem(val message: String) : Peeked
    }

    fun peek(context: Context, bytes: ByteArray, passphrase: String, replace: Boolean): Peeked {
        val opened = when (val o = open(bytes, passphrase)) {
            is Opened.Problem -> return Peeked.Problem(o.message)
            is Opened.Plain -> o
        }
        val steps = steps(context, opened.profiles, replace).filter { it.counted }
        val settings = opened.json.optJSONObject("settings")?.let { settingsFrom(it, SettingsStore.load(context), null) }
        return Peeked.Done(
            Peek(
                added = steps.filterIsInstance<Step.Add>().filter { it.old == null }.map { it.name },
                replaced = steps.filterIsInstance<Step.Add>().mapNotNull { it.old?.name },
                replacedIds = steps.filterIsInstance<Step.Add>().mapNotNull { it.old?.id },
                same = steps.filterIsInstance<Step.Same>().map { it.here.name },
                settings = settings,
            ),
        )
    }

    /** What happens to each profile in a backup: here already, or added (in place of [Step.Add.old] with replace). */
    private sealed interface Step {
        val p: JSONObject

        /** A build without WireGuard keeps those profiles but doesn't count or show them. */
        val counted: Boolean get() = !p.has("conf") || Features.WIREGUARD

        class Same(override val p: JSONObject, val here: Connection) : Step
        class Add(override val p: JSONObject, val name: String, val old: Connection?) : Step
    }

    /**
     * One profile that is here already, with the same name, file and login, is left alone. One that
     * only shares a name is added as "Name (restored)", or with [replace] takes the place of the one here.
     */
    private fun steps(context: Context, profiles: List<JSONObject>, replace: Boolean): List<Step> {
        val here = ConnectionStore.list(context)
        val kinds = ServerStore.list(context).associate { it.id to it.kind }
        val names = here.map { it.name }.toMutableSet()
        return profiles.map { p ->
            val name = p.getString("name")
            val wg = p.has("conf")
            val kind = if (wg) VpnKind.WireGuard else VpnKind.OpenVpn
            val file = if (wg) p.getString("conf") else p.getString("ovpn")
            val same = here.firstOrNull {
                it.name == name && kinds[it.serverId] == kind && ServerStore.effectiveConfig(context, it.serverId) == file &&
                    if (wg) CredentialStore.wireGuard(context, it.id) == keysOf(p)
                    else CredentialStore.load(context, it.id).let { l -> l.username == p.optString("username") && l.password == p.optString("password") }
            }
            if (same != null) return@map Step.Same(p, same)
            val old = if (replace) here.firstOrNull { it.name == name && kinds[it.serverId] == kind } else null
            var unique = name
            if (old == null && unique in names) {
                unique = "$name (restored)"
                var n = 2
                while (unique in names) unique = "$name (restored ${n++})"
            }
            names += unique
            Step.Add(p, unique, old)
        }
    }

    private fun keysOf(p: JSONObject): WireGuardFile.Secrets? = if (!p.has("conf")) null else
        WireGuardFile.Secrets(p.getString("privateKey"), p.optJSONObject("presharedKeys")?.let { k -> k.keys().asSequence().associateWith { k.getString(it) } }.orEmpty())

    /**
     * Adds every profile in the backup to this device, and with [withSettings] takes its settings.
     * A profile that is here already, with the same name, file and login, is left alone. One that
     * only shares a name is added as "Name (restored)", or with [replace] takes the place of the one here.
     */
    fun restore(context: Context, bytes: ByteArray, passphrase: String, replace: Boolean = false, withSettings: Boolean = true): Restored {
        val opened = when (val o = open(bytes, passphrase)) {
            is Opened.Problem -> return Restored.Problem(o.message)
            is Opened.Plain -> o
        }
        return runCatching { restore(context, opened.json, opened.profiles, replace, withSettings) }
            .getOrElse { Restored.Problem("Restoring stopped part way: ${it.message}") }
    }

    private fun restore(context: Context, plain: JSONObject, profiles: List<JSONObject>, replace: Boolean, withSettings: Boolean): Restored {
        var added = 0
        var already = 0
        var replaced = 0
        var startupId: String? = null
        var startupWas: String? = null
        for (step in steps(context, profiles, replace)) {
            val p = step.p
            val counted = step.counted
            if (step is Step.Same) {
                if (counted) already++
                if (p.optBoolean("startup")) startupId = step.here.id
                continue
            }
            step as Step.Add
            val old = step.old
            val unique = step.name
            val file = if (p.has("conf")) p.getString("conf") else p.getString("ovpn")
            val keys = keysOf(p)
            val server = ServerStore.add(context, file)
            val c = ConnectionStore.add(context, unique, server.id)
            if (p.optBoolean("startup")) startupId = c.id
            ConnectionStore.update(context, c.id) {
                it.copy(
                    pinned = p.optBoolean("pinned"),
                    killMode = p.optString("killMode", Connection.APP_SETTING),
                    dnsMode = p.optString("dnsMode", Connection.APP_SETTING),
                    order = p.optString("order", "In order"),
                    lookIcon = p.optString("lookIcon").takeIf { s -> s.isNotEmpty() && s != "null" },
                    lookColour = if (p.isNull("lookColour")) null else p.optInt("lookColour"),
                )
            }
            val user = p.optString("username")
            if (user.isNotEmpty()) CredentialStore.save(context, c.id, user, p.optString("password"))
            keys?.let { CredentialStore.saveWireGuard(context, c.id, it) }
            if (old != null) {
                // The one here goes, with its login, and its server unless another profile shares it.
                ConnectionStore.delete(context, old.id)
                CredentialStore.remove(context, old.id)
                if (ConnectionStore.list(context).none { it.serverId == old.serverId }) ServerStore.delete(context, old.serverId)
                if (SettingsStore.load(context).startupId == old.id) startupWas = c.id
                if (counted) replaced++
            } else if (counted) {
                added++
            }
        }
        val backupSettings = plain.optJSONObject("settings")
        if (withSettings && backupSettings != null) {
            SettingsStore.save(context, settingsFrom(backupSettings, SettingsStore.load(context), startupId ?: startupWas))
        } else if (startupWas != null) {
            // The startup profile was replaced: its replacement connects at startup instead.
            val now = SettingsStore.load(context)
            SettingsStore.save(context, now.copy(startupId = startupWas))
        }
        return Restored.Done(added, already, replaced)
    }

    /** The settings in a backup's [s] over [now]; [startup] is the restored startup profile here, if any. */
    private fun settingsFrom(s: JSONObject, now: AppSettings, startup: String?): AppSettings = now.copy(
        killSwitch = s.optBoolean("killSwitch", now.killSwitch),
        allowLan = s.optBoolean("allowLan", now.allowLan),
        blockIpv6 = s.optBoolean("blockIpv6", now.blockIpv6),
        blockDns = s.optBoolean("blockDns", now.blockDns),
        roam = s.optBoolean("roam", now.roam),
        appMode = s.optString("appMode", now.appMode),
        apps = s.optJSONArray("apps")?.let { a -> (0 until a.length()).map(a::getString).toSet() } ?: now.apps,
        proto = s.optString("proto", now.proto),
        timeout = s.optInt("timeout", now.timeout),
        mtu = s.optString("mtu", now.mtu),
        tlsMin = s.optString("tlsMin", now.tlsMin),
        pushedDns = s.optBoolean("pushedDns", now.pushedDns),
        customDns = s.optJSONArray("customDns")?.let { a -> (0 until a.length()).map(a::getString) } ?: now.customDns,
        searchDomains = s.optJSONArray("searchDomains")?.let { a -> (0 until a.length()).map(a::getString) } ?: now.searchDomains,
        // An older backup has no startup profile; then this device keeps its own.
        boot = if (s.has("boot")) s.optBoolean("boot") && (startup ?: now.startupId) != null else now.boot,
        startupId = startup ?: now.startupId,
        stayAwake = s.optBoolean("stayAwake", now.stayAwake),
        legacyCiphers = s.optBoolean("legacyCiphers", now.legacyCiphers),
        compress = s.optBoolean("compress", now.compress),
        verb = s.optString("verb", now.verb),
        advanced = s.optBoolean("advanced", now.advanced),
        automation = s.optBoolean("automation", now.automation),
        autoUpdate = s.optBoolean("autoUpdate", now.autoUpdate),
    )

    private fun key(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
