package dev.shieldvpn.vpn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.VpnService
import android.os.Build
import androidx.core.content.IntentCompat
import dev.shieldvpn.APP_NAME
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the project's GitHub releases. Each release carries update.json beside its APKs:
 * its build number (the versionCode), commit, and each APK's name, size and SHA-256 by ABI
 * (`tools/release.sh` writes it). A build higher than the installed one is an update, even under
 * the same version name: during the beta, builds are republished under one version.
 *
 * Android only installs an update signed with the same key as the installed app.
 */
object Updates {

    const val RELEASES = "https://api.github.com/repos/kanin-design/GoogleTV-VPN/releases?per_page=5"

    /** Where the list of releases comes from. Debug builds can point it at a test server. */
    @Volatile var source = RELEASES

    /** Why the last install failed, set by [UpdateReceiver] for the screens to show. */
    val installFailure = MutableStateFlow<String?>(null)

    /** A published build: its APK for this device, where to get it, and what it must hash to. */
    data class Published(
        val version: String,
        val number: Int,
        val commit: String,
        val apk: String,
        val url: String,
        val size: Long,
        val sha256: String,
    )

    /**
     * The newest published build with an APK for this device, or null if none has one. It is the
     * newest release that carries update.json. Throws when GitHub cannot be reached.
     */
    fun newest(): Published? {
        val releases = JSONArray(String(fetch(source) ?: error("No answer from GitHub")))
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val assets = release.getJSONArray("assets")
            val urls = (0 until assets.length()).map { assets.getJSONObject(it) }
                .associate { it.getString("name") to it.getString("browser_download_url") }
            val manifest = JSONObject(String(fetch(urls["update.json"] ?: continue) ?: continue))
            val apks = manifest.getJSONObject("apks")
            val abi = Build.SUPPORTED_ABIS.firstOrNull { apks.has(it) } ?: return null
            val apk = apks.getJSONObject(abi)
            return Published(
                version = manifest.getString("version"),
                number = manifest.getInt("build"),
                commit = manifest.optString("commit"),
                apk = apk.getString("name"),
                url = urls[apk.getString("name")] ?: return null,
                size = apk.getLong("size"),
                sha256 = apk.getString("sha256").lowercase(),
            )
        }
        return null
    }

    /**
     * Downloads [build]'s APK to [file], reporting whole percents to [progress]. Returns what went
     * wrong, or null when the file is complete and its SHA-256 matches.
     */
    fun download(build: Published, file: File, progress: (Int) -> Unit): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val c = URL(build.url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            if (c.responseCode != 200) return "The download failed (HTTP ${c.responseCode}). Try again later."
            var done = 0L
            var shown = -1
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > build.size) return "The download is larger than it should be."
                        val percent = (done * 100 / build.size).toInt()
                        if (percent != shown) {
                            shown = percent
                            progress(percent)
                        }
                    }
                }
            }
            if (done != build.size) return "The download stopped early. Try again."
        } catch (e: java.io.IOException) {
            return "The download stopped: ${e.message ?: "no connection"}."
        } finally {
            c.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return if (sha == build.sha256) null else "The download doesn't match its checksum, so it was not installed."
    }

    /**
     * Hands [apk] to Android's package installer. Android asks the user to confirm (through
     * [UpdateReceiver]); on Android 12 and later it may not, once this app installed the last build.
     */
    fun install(context: Context, apk: File) {
        installFailure.value = null
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Where the app is now: internal storage, or a USB drive the TV uses as its own storage.
            // Unset, Android asks for internal storage only and refuses an app that is on a drive.
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val done = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or mutable)
            session.commit(done.intentSender)
        }
    }

    private fun fetch(url: String): ByteArray? =
        Http.get(url, mapOf("Accept" to "application/vnd.github+json", "User-Agent" to APP_NAME), timeoutMs = 10_000, limit = 1024 * 1024).body
}

/** How an update's install went. Android may want the user to confirm it first. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // Android replaces the app and ends its processes; nothing more to do here.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                // The old build stays, so its tunnel never ended: nothing to connect again.
                SettingsStore.setReconnectAfterUpdate(context, null)
                Updates.installFailure.value = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled."
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This build can't replace the installed one: it was signed with another key."
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android could not install the update."
                }
            }
        }
    }
}

/** The new build is in: connects again the tunnel that installing it ended, as the boot receiver does. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val id = SettingsStore.takeReconnectAfterUpdate(context) ?: return
        if (VpnService.prepare(context) != null) return
        val plan = Tunnels.plan(context, id) as? Tunnels.Plan.Ready ?: return
        Tunnels.start(context, plan)
    }
}
