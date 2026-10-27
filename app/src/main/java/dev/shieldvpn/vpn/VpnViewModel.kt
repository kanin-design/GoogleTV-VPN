package dev.shieldvpn.vpn

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.BuildConfig
import dev.shieldvpn.control.Control
import dev.shieldvpn.control.CoreState
import dev.shieldvpn.control.Detect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the screens show: [Control]'s state, mirrored at once on every change, plus what only the
 * screens have (an update on offer, the file under review, a backup or .p12 waiting for its
 * password). The app's data is changed through [Control], not here.
 */
class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    private val app: Application get() = getApplication()

    private val stopListening: () -> Unit

    init {
        Control.init(application)
        Control.attachLink()
        stopListening = Control.listen { core -> _state.update { it.mirror(core) } }
        viewModelScope.launch { VpnController.status.collect { s -> _state.update { it.copy(status = s) } } }
        viewModelScope.launch { VpnController.tunnel.collect { t -> _state.update { it.copy(tunnel = t) } } }
        viewModelScope.launch { VpnController.pausedUntil.collect { until -> _state.update { it.copy(pausedUntil = until) } } }
        viewModelScope.launch {
            Updates.installFailure.collect { why -> if (why != null) _state.update { it.copy(update = UpdateState.Failed(why)) } }
        }
        // An update that was installed leaves its APK behind; then, each time the app opens, look for the next.
        File(app.cacheDir, UPDATE_APK).delete()
        if (_state.value.settings.autoUpdate) {
            viewModelScope.launch {
                delay(5_000)
                checkUpdate(atStart = true)
            }
        }
    }

    private fun VpnUiState.mirror(core: CoreState) = copy(
        connections = core.connections, servers = core.servers, logins = core.logins, infos = core.infos,
        activeId = core.activeId, settings = core.settings, hasBackupPassphrase = core.hasBackupPassphrase,
        hasPin = core.hasPin, lastBackup = core.lastBackup, speeds = core.speeds,
    )

    override fun onCleared() {
        stopListening()
        Control.detachLink()
    }

    // ---- Updates ------------------------------------------------------------

    /** Looks on GitHub for a newer build. The check when the app opens ([atStart]) says nothing unless it finds one. */
    fun checkUpdate(atStart: Boolean = false) {
        when (_state.value.update) {
            UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Installing -> return
            else -> Unit
        }
        if (!atStart) _state.update { it.copy(update = UpdateState.Checking) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Updates.newest() } }
            val next = found.fold(
                onSuccess = { b ->
                    if (b == null || b.number <= BuildConfig.VERSION_CODE) UpdateState.UpToDate
                    else UpdateState.Available(b, found = atStart, newKey = b.signer != null && b.signer != Updates.signer(app))
                },
                onFailure = { UpdateState.Failed("GitHub didn't answer. Try again later.") },
            )
            // The check at startup only reports what it found; a failure or no news waits for the next start.
            if (!atStart || next is UpdateState.Available) _state.update { it.copy(update = next) }
        }
    }

    /** Downloads the newer build, checks it and hands it to Android's installer. */
    fun installUpdate() {
        val available = _state.value.update as? UpdateState.Available ?: return
        // Android would refuse it at the end; the screens show the steps instead.
        if (available.newKey) return
        val build = available.build
        _state.update { it.copy(update = UpdateState.Downloading(build, 0)) }
        viewModelScope.launch {
            val file = File(app.cacheDir, UPDATE_APK)
            val problem = withContext(Dispatchers.IO) {
                Updates.download(build, file) { percent -> _state.update { it.copy(update = UpdateState.Downloading(build, percent)) } }
            }
            if (problem != null) {
                file.delete()
                _state.update { it.copy(update = UpdateState.Failed(problem)) }
                return@launch
            }
            _state.update { it.copy(update = UpdateState.Installing(build)) }
            installStarted = System.currentTimeMillis()
            // Installing ends the tunnel along with this process; the new build connects it again.
            val up = when (val s = _state.value.status) {
                is VpnStatus.Connected -> s.connectionId
                is VpnStatus.Connecting -> s.connectionId
                is VpnStatus.Blocked -> s.connectionId
                else -> null
            }
            val failed = withContext(Dispatchers.IO) {
                SettingsStore.setReconnectAfterUpdate(app, up)
                runCatching { Updates.install(app, file) }.exceptionOrNull()?.also { SettingsStore.setReconnectAfterUpdate(app, null) }
            }
            if (failed != null) {
                val why = if (failed.message.orEmpty().contains("space", ignoreCase = true)) {
                    "There isn't room for the update where ${APP_NAME} is installed. Free some space and try again."
                } else {
                    "Android could not start the install: ${failed.message}"
                }
                _state.update { it.copy(update = UpdateState.Failed(why)) }
            }
        }
    }

    /**
     * The screens are back. An install still "in progress" means Android's confirm was closed or
     * never shown (the process would have ended with the update), so the update is offered again.
     */
    fun screensShown() {
        val u = _state.value.update
        if (u is UpdateState.Installing && System.currentTimeMillis() - installStarted > 3_000) {
            _state.update { it.copy(update = UpdateState.Available(u.build)) }
        }
    }

    private var installStarted = 0L

    // ---- The tunnel ---------------------------------------------------------

    /**
     * Debug builds, for screenshots: the active profile looks connected, with addresses from the
     * ranges kept for documentation. Nothing connects, and the next real change replaces it.
     */
    fun showDemo(state: String? = null) {
        val id = _state.value.activeId ?: return
        when (state) {
            "Busy" -> return VpnController.update(VpnStatus.Connecting(id))
            "Blocked" -> {
                VpnController.setTunnel(null)
                return VpnController.update(VpnStatus.Blocked(id))
            }
            "Off" -> {
                VpnController.setTunnel(null)
                return VpnController.update(VpnStatus.Disconnected)
            }
        }
        VpnController.setTunnel(
            TunnelInfo(
                address = "10.8.0.6/30", dns = listOf("10.8.0.1"), routes = listOf("All traffic"), mtu = 1500,
                remote = "198.51.100.20", publicIp = "198.51.100.20",
            ),
        )
        VpnController.update(VpnStatus.Connected(id, System.currentTimeMillis() - (3 * 3600 + 17 * 60) * 1000L))
    }

    // ---- Importing ------------------------------------------------------------

    /** A file picked on the TV: a profile opens its review, a backup asks for its passphrase. */
    fun review(uri: Uri) {
        val name = runCatching { ServerStore.fileNameOf(app, uri) }.getOrDefault("")
        val bytes = runCatching { app.contentResolver.openInputStream(uri)?.use { ServerStore.readLimited(it) } }.getOrNull()
        val problem = if (bytes == null) "${APP_NAME} could not open this file." else receive(name, bytes)
        if (problem != null) {
            _state.update { it.copy(review = ImportReview(name, "", "", "", 0, Protocol.UDP, false, "", "", emptyList(), problem)) }
        }
    }

    fun noFilePicker() {
        _state.update {
            it.copy(review = ImportReview("", "", "", "", 0, Protocol.UDP, false, "", "", emptyList(), "This device has no file picker, so ${APP_NAME} can’t open files from Downloads or USB. Send the file from your phone instead."))
        }
    }

    fun cancelReview() {
        _state.update { it.copy(review = null) }
    }

    fun renameReview(name: String) {
        if (name.isBlank()) return
        _state.update { it.copy(review = it.review?.copy(name = name.trim())) }
    }

    fun inspect(name: String, bytes: ByteArray): Inspected = Detect.inspect(name, bytes).first

    /** Shows [review] on the TV, to be saved there or from the phone. */
    fun openReview(review: ImportReview) {
        _state.update { it.copy(review = review) }
    }

    /**
     * A file or text from a phone or a link. A backup waits for its passphrase; anything else
     * opens the review. Returns what is wrong, for the phone to show, or null when it arrived.
     */
    fun receive(name: String, raw: ByteArray, username: String = "", password: String = ""): String? {
        val (name, bytes) = when (val u = Detect.unwrap(name, raw)) {
            is Detect.Unwrapped.Problem -> return u.message
            is Detect.Unwrapped.File -> u.name to u.bytes
        }
        if (Backup.isBackup(bytes)) {
            _state.update { it.copy(pendingRestore = bytes) }
            return null
        }
        if (Certificates.isPkcs12(name, bytes)) {
            _state.update { it.copy(pendingP12 = bytes) }
            return null
        }
        var review = ServerStore.review(name.ifBlank { "pasted.ovpn" }, bytes)
        if (review.problem != null) return review.problem
        // A login typed on the phone goes with a profile that asks for one and does not hold it.
        if (review.needsLogin && review.username.isEmpty() && username.isNotBlank() && password.isNotEmpty()) {
            review = review.copy(username = username.trim(), password = password, loginFromPhone = true)
        }
        _state.update { it.copy(review = review) }
        return null
    }

    /** Downloads a profile from [url], with a login when the link needs one. Returns what went wrong, or null. */
    suspend fun download(url: String, username: String?, password: String?): String? =
        when (val d = Control.download(url, username, password)) {
            is Control.Downloaded.File -> receive(d.name, d.bytes)
            is Control.Downloaded.Problem -> d.message
        }

    /** Opens the waiting .p12 with [password]; null when it opens, or what is wrong. */
    suspend fun checkP12(password: String): String? {
        val bytes = _state.value.pendingP12 ?: return "There is no certificate waiting."
        return Control.checkP12(bytes, password)
    }

    /** Puts the waiting .p12's certificate and key into profile [id]'s file. Returns the problem, or null. */
    suspend fun attachP12(password: String, id: String): String? {
        val bytes = _state.value.pendingP12 ?: return "There is no certificate waiting."
        val problem = Control.attachP12(bytes, password, id)
        if (problem == null) _state.update { it.copy(pendingP12 = null) }
        return problem
    }

    /** Moves the waiting .p12's key into Android's keystore. Returns the alias, or null. */
    suspend fun keepP12(password: String): String? {
        val bytes = _state.value.pendingP12 ?: return null
        val alias = Control.keepP12(bytes, password) ?: return null
        _state.update { it.copy(pendingP12 = null) }
        return alias
    }

    fun cancelP12() {
        _state.update { it.copy(pendingP12 = null) }
    }

    fun cancelRestore() {
        _state.update { it.copy(pendingRestore = null) }
    }

    /** Restores the waiting backup; with [replace], profiles with the same name are replaced. */
    suspend fun restore(passphrase: String, replace: Boolean = false): Backup.Restored {
        val bytes = _state.value.pendingRestore ?: return Backup.Restored.Problem("There is no backup to restore.")
        val result = Control.restore(bytes, passphrase, replace)
        if (result is Backup.Restored.Done) _state.update { it.copy(pendingRestore = null) }
        return result
    }

    /** Saves the reviewed file as a new profile and returns its id. */
    fun saveReview(): String? {
        val review = _state.value.review ?: return null
        val id = Control.addProfile(review) ?: return null
        _state.update { it.copy(review = null) }
        return id
    }

    /**
     * The connection log, apart from the rest of the state: it changes with every line while a
     * tunnel connects, and only the log's own views need it.
     */
    val log: StateFlow<List<LogLine>> get() = VpnController.log

    /** The log as a file: what it came from first, so it says something on its own. */
    fun logText(): String {
        val s = _state.value
        val profile = s.active?.let { c -> "${c.name} (${dev.shieldvpn.control.Words.kind(s.infoOf(c)?.kind ?: VpnKind.OpenVpn)})" } ?: "none"
        val head = "$APP_NAME ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL} · profile $profile"
        return head + "\n\n" + VpnController.log.value.joinToString("\n") { "${it.time}  ${it.text}" } + "\n"
    }

    companion object {
        private const val UPDATE_APK = "update.apk"
    }
}
