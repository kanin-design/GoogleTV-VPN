package dev.shieldvpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.security.KeyChain
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dev.shieldvpn.ui.tv.LauncherApp
import dev.shieldvpn.ui.tv.TunnelActions
import dev.shieldvpn.ui.tv.TunnelApp
import dev.shieldvpn.vpn.AppSettings
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.CredentialStore
import dev.shieldvpn.vpn.ShieldVpnService
import dev.shieldvpn.vpn.VpnController
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.SpeedTest
import dev.shieldvpn.vpn.Tunnels
import dev.shieldvpn.vpn.VpnViewModel

class MainActivity : ComponentActivity(), TunnelActions {

    private val viewModel: VpnViewModel by viewModels()

    /** A connect waiting for the user to allow VPN access. */
    private class PendingConnect(val id: String, val login: CredentialStore.Credentials?, val remember: Boolean)

    private var pending: PendingConnect? = null

    // The screens close themselves ten minutes after you leave them, and their process ends, so
    // the TV gets the memory back. The tunnel runs in its own process and keeps going.
    private val away = Handler(Looper.getMainLooper())
    private val closeAway = Runnable { closeWhileAway() }
    private var closeAfterMs = CLOSE_AFTER_MS
    private var closedAway = false

    // A system screen answers this activity later: the VPN consent, a file picker, the certificate chooser.
    private var waitingForAnswer = false

    // Android asks the user once to allow this app to create a VPN.
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        waitingForAnswer = false
        val p = pending ?: return@registerForActivityResult
        pending = null
        if (result.resultCode == Activity.RESULT_OK) {
            start(p.id, p.login, p.remember)
        } else {
            VpnController.update(VpnStatus.Failed(p.id, "VPN permission was declined"))
        }
    }

    // The system file picker. .ovpn has no standard MIME type, so any file can be picked
    // and the review checks the contents.
    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        waitingForAnswer = false
        uri?.let(viewModel::review)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            TunnelApp(state = state, actions = this)
        }

        debugExtras(intent)
    }

    override fun onStart() {
        super.onStart()
        away.removeCallbacks(closeAway)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) away.postDelayed(closeAway, closeAfterMs)
    }

    private fun closeWhileAway() {
        val s = viewModel.state.value
        // Stay while something unsaved waits on the screens: a file to review, a backup or certificate to unlock.
        if (waitingForAnswer || s.review != null || s.pendingRestore != null || s.pendingP12 != null) return
        closedAway = true
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        away.removeCallbacks(closeAway)
        super.onDestroy()
        // Nothing else lives in this process, so end it instead of leaving it cached.
        if (closedAway) away.post { Process.killProcess(Process.myPid()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugExtras(intent)
    }

    /**
     * Debug builds only, for testing: `am start ... --ez autoconnect true` presses Connect,
     * `--ez simulatedrop true` ends the tunnel after a delay, `--ez dumpthreads true` logs threads,
     * `--ei pauseseconds N` pauses the tunnel for N seconds instead of 15 minutes,
     * `--ei closeafterseconds N` closes the screens N seconds after they leave instead of 10 minutes,
     * `--ez demo true` shows a made-up connected tunnel for screenshots; nothing connects
     * (`--es demostate Busy`, `Off` or `Blocked` shows connecting, not connected or the kill
     * switch blocking traffic instead),
     * `--es hover "X,Y X,Y"` glides a mouse pointer through those points in screen pixels, resting
     * 1.5 s at each, and `--es hover off` takes it away.
     */
    private fun debugExtras(intent: Intent) {
        if (isDebuggable()) {
            if (intent.getBooleanExtra(EXTRA_AUTOCONNECT, false)) {
                window.decorView.post { viewModel.state.value.activeId?.let { connect(it) } }
            }
            if (intent.getBooleanExtra(EXTRA_SIMULATE_DROP, false)) {
                window.decorView.postDelayed({ startService(ShieldVpnService.simulateDropIntent(this)) }, 12_000)
            }
            if (intent.getBooleanExtra("demo", false)) {
                // After the link has reported the real state, which is then left alone while idle.
                val demoState = intent.getStringExtra("demostate")
                window.decorView.postDelayed({ viewModel.showDemo(demoState) }, if (demoState == null) 1500L else 0L)
                intent.removeExtra("demo")
            }
            intent.getIntExtra("closeafterseconds", 0).takeIf { it > 0 }?.let { seconds ->
                closeAfterMs = seconds * 1000L
                intent.removeExtra("closeafterseconds")
            }
            intent.getIntExtra("pauseseconds", 0).takeIf { it > 0 }?.let { seconds ->
                Tunnels.pause(this, System.currentTimeMillis() + seconds * 1000L)
                intent.removeExtra("pauseseconds")
            }
            // The emulator has no mouse (its clicks arrive as touches), so hover is tested with this.
            intent.getStringExtra("hover")?.let { at ->
                window.decorView.post { fakeHover(at) }
                intent.removeExtra("hover")
            }
            // `--ez dumpthreads true` logs every thread's stack, to find leaks.
            if (intent.getBooleanExtra("dumpthreads", false)) {
                Thread.getAllStackTraces().forEach { (t, stack) ->
                    android.util.Log.i("TunnelThreads", "${t.name} (${t.state}): " + stack.take(14).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" })
                }
                intent.removeExtra("dumpthreads")
            }
            // Clear the flags, so a recreated activity does not repeat the test.
            intent.removeExtra(EXTRA_AUTOCONNECT)
            intent.removeExtra(EXTRA_SIMULATE_DROP)
        }
    }

    private fun isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Where the fake mouse pointer rests, or null while there is none. */
    private var hoverAt: Pair<Float, Float>? = null

    /** Debug builds: sends the hover events of a mouse gliding through [at] ("X,Y X,Y", or "off"). */
    private fun fakeHover(at: String) {
        fun send(action: Int, x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
            val event = MotionEvent.obtain(now, now, action, 1, arrayOf(pointer), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            window.decorView.dispatchGenericMotionEvent(event)
            event.recycle()
        }
        val last = hoverAt
        if (at == "off") {
            last?.let { (x, y) -> send(MotionEvent.ACTION_HOVER_EXIT, x, y) }
            hoverAt = null
            return
        }
        val view = window.decorView
        var from = last
        var start = 0L
        for (point in at.trim().split(Regex("\\s+"))) {
            val (x, y) = point.split(',').map { it.trim().toFloat() }
            val a = from
            if (a == null) {
                view.postDelayed({ send(MotionEvent.ACTION_HOVER_ENTER, x, y); send(MotionEvent.ACTION_HOVER_MOVE, x, y) }, start)
            } else {
                // Twelve steps, as a real mouse reports its way across the screen.
                for (i in 1..12) {
                    val f = i / 12f
                    view.postDelayed({ send(MotionEvent.ACTION_HOVER_MOVE, a.first + (x - a.first) * f, a.second + (y - a.second) * f) }, start + i * 20L)
                }
            }
            from = x to y
            start += 1500L
        }
        hoverAt = from
    }

    private fun start(id: String, login: CredentialStore.Credentials?, remember: Boolean) {
        if (!viewModel.connect(id, login, remember)) {
            VpnController.update(VpnStatus.Failed(id, "Add the username and password first"))
        }
    }

    // ---- TunnelActions ------------------------------------------------------

    override fun connect(id: String, username: String?, password: String?, remember: Boolean) {
        val login = if (username != null && password != null) CredentialStore.Credentials(username, password) else null
        val consent = VpnService.prepare(this)
        if (consent == null) {
            start(id, login, remember)
        } else {
            pending = PendingConnect(id, login, remember)
            waitingForAnswer = true
            consentLauncher.launch(consent)
        }
    }

    override fun disconnect() = viewModel.disconnect()

    override fun saveSpeed(key: String, result: SpeedTest.Result) = viewModel.saveSpeed(key, result)

    override fun pause() = viewModel.pause()

    override fun pauseMore() = viewModel.pauseMore()

    override fun resume() = viewModel.resume()

    override fun rename(id: String, name: String) = viewModel.rename(id, name)

    override fun saveLogin(id: String, username: String, password: String) = viewModel.saveLogin(id, username, password)

    override fun forgetPassword(id: String) = viewModel.forgetPassword(id)

    override fun setServer(id: String, host: String, port: Int) = viewModel.setServer(id, host, port)

    override fun resetServer(id: String) = viewModel.resetServer(id)

    override fun setProtocol(id: String, value: String) = viewModel.setProtocol(id, value)

    override fun setKillMode(id: String, value: String) = viewModel.setKillMode(id, value)

    override fun setDnsMode(id: String, value: String) = viewModel.setDnsMode(id, value)

    override fun setOrder(id: String, value: String) = viewModel.setOrder(id, value)

    override fun setPinned(id: String, pinned: Boolean) = viewModel.setPinned(id, pinned)
    override fun setLook(id: String, icon: String?, colour: Int?) = viewModel.setLook(id, icon, colour)

    override fun setStartup(id: String?) = viewModel.setStartup(id)

    override fun duplicate(id: String): String? = viewModel.duplicate(id)?.id

    override fun delete(id: String) = viewModel.delete(id)

    override fun pickFile() {
        // Many TVs have no file picker. Android's own answer is a vague toast, so look first.
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        // Android TV answers with a stub that only says no app can do this; that is no picker.
        val handler = picker.resolveActivity(packageManager)
        if (handler == null || handler.packageName == "com.android.tv.frameworkpackagestubs") {
            viewModel.noFilePicker()
            return
        }
        try {
            waitingForAnswer = true
            pickLauncher.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            waitingForAnswer = false
            viewModel.noFilePicker()
        }
    }

    override fun cancelReview() = viewModel.cancelReview()

    override fun renameReview(name: String) = viewModel.renameReview(name)

    override fun saveReview(): String? = viewModel.saveReview()

    override fun rawConfig(serverId: String): String = viewModel.rawConfig(serverId)

    override fun changeSettings(change: (AppSettings) -> AppSettings) = viewModel.changeSettings(change)

    override fun clearLog() = viewModel.clearLog()

    override fun resetAll() = viewModel.resetAll()

    override fun receive(name: String, bytes: ByteArray): String? = viewModel.receive(name, bytes)

    override suspend fun download(url: String, username: String?, password: String?): String? = viewModel.download(url, username, password)

    override fun cancelRestore() = viewModel.cancelRestore()

    override suspend fun restore(passphrase: String): Backup.Restored = viewModel.restore(passphrase)

    override fun setBackupPassphrase(passphrase: String?) = viewModel.setBackupPassphrase(passphrase)

    override suspend fun backupFile(): ByteArray? = viewModel.backupFile()

    override fun profileFile(id: String): ByteArray? = viewModel.profileFile(id)

    override fun logText(): String = viewModel.logText()

    override fun setPin(pin: String?) = viewModel.setPin(pin)

    override fun checkPin(pin: String): Boolean = viewModel.checkPin(pin)

    override fun checkP12(password: String): String? = viewModel.checkP12(password)

    override fun attachP12(password: String, id: String): String? = viewModel.attachP12(password, id)

    override fun cancelP12() = viewModel.cancelP12()

    override fun keepP12(password: String): String? = viewModel.keepP12(password)

    override fun setKeyAlias(id: String, alias: String?) = viewModel.setKeyAlias(id, alias)

    override fun chooseKeyAlias(chosen: (String?) -> Unit) {
        try {
            waitingForAnswer = true
            KeyChain.choosePrivateKeyAlias(this, { alias ->
                runOnUiThread {
                    waitingForAnswer = false
                    chosen(alias)
                }
            }, null, null, null, null)
        } catch (_: ActivityNotFoundException) {
            waitingForAnswer = false
            chosen(null)
        }
    }

    override fun launcherApps(): List<LauncherApp> {
        val pm = packageManager
        // The icons are drawn 40 design units wide: 60 pixels in Android TV's 1080p window, 120 in a 4K one.
        val iconPx = (40 * resources.displayMetrics.widthPixels / 1280f).toInt().coerceAtLeast(40)
        val found = LinkedHashMap<String, LauncherApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            pm.queryIntentActivities(intent, 0).forEach { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || pkg in found) return@forEach
                // Exactly the size they are shown at: sharp, and no bigger than that in memory.
                val icon = runCatching { info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull()
                found[pkg] = LauncherApp(pkg, info.loadLabel(pm).toString(), icon)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    override fun openVpnSettings(): Boolean =
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    override fun exit() = finish()

    override val versionName: String
        get() = packageManager.getPackageInfo(packageName, 0).versionName ?: ""

    companion object {
        private const val EXTRA_AUTOCONNECT = "autoconnect"
        private const val EXTRA_SIMULATE_DROP = "simulatedrop"
        private const val CLOSE_AFTER_MS = 10 * 60 * 1000L
    }
}
