package dev.shieldvpn.vpn

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()

    private val app: Application get() = getApplication()

    // The tunnel's state, from the VPN's own process.
    private val link = VpnLink.Client(application)

    init {
        link.open()
        _state.update { it.copy(settings = SettingsStore.load(app), speeds = SpeedTest.load(app)) }
        refresh(preferActive = SettingsStore.activeConnection(app))
        viewModelScope.launch { VpnController.status.collect { onStatus(it) } }
        viewModelScope.launch { VpnController.log.collect { log -> _state.update { it.copy(log = log) } } }
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
                onSuccess = { b -> if (b != null && b.number > BuildConfig.VERSION_CODE) UpdateState.Available(b, found = atStart) else UpdateState.UpToDate },
                onFailure = { UpdateState.Failed("GitHub didn't answer. Try again later.") },
            )
            // The check at startup only reports what it found; a failure or no news waits for the next start.
            if (!atStart || next is UpdateState.Available) _state.update { it.copy(update = next) }
        }
    }

    /** Downloads the newer build, checks it and hands it to Android's installer. */
    fun installUpdate() {
        val build = (_state.value.update as? UpdateState.Available)?.build ?: return
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
            if (failed != null) _state.update { it.copy(update = UpdateState.Failed("Android could not start the install: ${failed.message}")) }
        }
    }

    // ---- The tunnel ---------------------------------------------------------

    /**
     * Connects [id]. A [login] typed in the sign-in sheet is saved first when [remember]
     * is on, or used just this once. Returns false when the profile still needs a login.
     * The caller has made sure Android allows the VPN.
     */
    fun connect(id: String, login: CredentialStore.Credentials? = null, remember: Boolean = true): Boolean {
        setActive(id)
        if (login != null && remember) {
            CredentialStore.save(app, id, login.username.trim(), login.password)
            refresh()
        }
        return when (val plan = Tunnels.plan(app, id, login?.takeIf { !remember })) {
            is Tunnels.Plan.Ready -> {
                Tunnels.start(app, plan)
                true
            }
            is Tunnels.Plan.NeedsLogin -> false
            is Tunnels.Plan.Fails -> {
                failEarly(id, plan.message)
                true
            }
        }
    }

    fun disconnect() {
        Tunnels.stop(app)
    }

    /** Turns the tunnel off for 15 minutes. The service brings it back, even with the app closed. */
    fun pause() {
        Tunnels.pause(app, System.currentTimeMillis() + PAUSE_MILLIS)
    }

    fun pauseMore() {
        val until = _state.value.pausedUntil ?: return
        Tunnels.pause(app, until + PAUSE_MILLIS)
    }

    fun resume() {
        val id = _state.value.activeId ?: return
        connect(id)
    }

    /** A connect that fails before any tunnel starts. It must not disturb a tunnel that is still up. */
    private fun failEarly(id: String, message: String) {
        if (_state.value.status.isActive) {
            ConnectionStore.update(app, id) { it.copy(lastError = message) }
            refresh()
        } else {
            VpnController.update(VpnStatus.Failed(id, message))
        }
    }

    private fun onStatus(status: VpnStatus) {
        _state.update { it.copy(status = status) }
        // A tunnel started without these screens, at boot or by another app, is the one Connect shows.
        status.connectionId
            ?.takeIf { status.isActive && it != _state.value.activeId && _state.value.connection(it) != null }
            ?.let(::setActive)
        // Each profile remembers why it last failed, until it connects again.
        when (status) {
            is VpnStatus.Failed -> status.connectionId?.let { id ->
                if (_state.value.connection(id)?.lastError != status.message) {
                    ConnectionStore.update(app, id) { it.copy(lastError = status.message) }
                    refresh()
                }
            }
            is VpnStatus.Connected -> {
                ConnectionStore.update(app, status.connectionId) { it.copy(lastError = null, lastUsed = System.currentTimeMillis()) }
                refresh()
            }
            else -> Unit
        }
    }

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

    /** Keeps [r] as the last speed test of profile [key], or of the connection without the VPN. */
    fun saveSpeed(key: String, r: SpeedTest.Result) {
        SpeedTest.save(app, key, r)
        _state.update { it.copy(speeds = it.speeds + (key to r)) }
    }

    // ---- Profiles -------------------------------------------------------------

    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        ConnectionStore.update(app, id) { it.copy(name = clean) }
        refresh()
    }

    fun saveLogin(id: String, username: String, password: String) {
        CredentialStore.save(app, id, username.trim(), password)
        refresh()
    }

    fun forgetPassword(id: String) {
        CredentialStore.save(app, id, CredentialStore.load(app, id).username, "")
        if (_state.value.settings.startupId == id) changeSettings { it.copy(startupId = null, boot = false) }
        refresh()
    }

    fun setServer(id: String, host: String, port: Int) {
        val server = ownServer(id) ?: return
        ServerStore.update(app, server.copy(host = host.trim(), port = port))
        refresh()
    }

    fun resetServer(id: String) {
        val server = ownServer(id) ?: return
        ServerStore.resetToFile(app, server.id)
        refresh()
    }

    /**
     * The server file profile [id] can change alone. Profiles made with Duplicate share one; the
     * first change gives this profile its own copy, so the others keep their address and protocol.
     */
    private fun ownServer(id: String): Server? {
        val c = ConnectionStore.list(app).firstOrNull { it.id == id } ?: return null
        val server = ServerStore.list(app).firstOrNull { it.id == c.serverId } ?: return null
        if (ConnectionStore.list(app).none { it.id != id && it.serverId == c.serverId }) return server
        val raw = ServerStore.rawConfig(app, server.id) ?: return server
        val own = ServerStore.add(app, raw)
        ServerStore.update(app, own.copy(host = server.host, port = server.port, protocol = server.protocol))
        ConnectionStore.update(app, id) { it.copy(serverId = own.id) }
        refresh()
        return ServerStore.list(app).first { it.id == own.id }
    }

    /** "From file", "UDP" or "TCP". */
    fun setProtocol(id: String, value: String) {
        val c = _state.value.connection(id) ?: return
        val server = ownServer(id) ?: return
        val protocol = when (value) {
            "UDP" -> Protocol.UDP
            "TCP" -> Protocol.TCP
            else -> _state.value.infoOf(c)?.fileProtocol ?: server.protocol
        }
        ServerStore.update(app, server.copy(protocol = protocol))
        refresh()
    }

    fun setKillMode(id: String, value: String) = changeConnection(id) { it.copy(killMode = value) }

    fun setDnsMode(id: String, value: String) = changeConnection(id) { it.copy(dnsMode = value) }

    fun setOrder(id: String, value: String) = changeConnection(id) { it.copy(order = value) }

    fun setPinned(id: String, pinned: Boolean) = changeConnection(id) { it.copy(pinned = pinned) }

    /** The icon and colour shown for the profile; null for both picks them from its name again. */
    fun setLook(id: String, icon: String?, colour: Int?) = changeConnection(id) { it.copy(lookIcon = icon, lookColour = colour) }

    /** The profile that connects when the TV starts, or null for none. */
    fun setStartup(id: String?) = changeSettings { it.copy(startupId = id, boot = id != null) }

    private fun changeConnection(id: String, change: (Connection) -> Connection) {
        ConnectionStore.update(app, id, change)
        refresh()
    }

    /** Makes a copy right after the original, with the same login. Returns the copy. */
    fun duplicate(id: String): Connection? {
        val source = _state.value.connection(id) ?: return null
        val copy = source.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${source.name} (copy)",
            pinned = false,
            lastError = null,
            lastUsed = null,
        )
        ConnectionStore.insertAfter(app, id, copy)
        CredentialStore.copy(app, id, copy.id)
        refresh()
        return copy
    }

    fun delete(id: String) {
        val state = _state.value
        val connection = state.connection(id) ?: return
        if (state.status.connectionId == id) {
            if (state.status.isActive) Tunnels.stop(app) else VpnController.update(VpnStatus.Disconnected)
        }
        ConnectionStore.delete(app, id)
        CredentialStore.remove(app, id)
        // A server goes with its last profile, and so does a key Tunnel kept for it.
        if (ConnectionStore.list(app).none { it.serverId == connection.serverId }) ServerStore.delete(app, connection.serverId)
        connection.keyAlias?.let(::releaseKey)
        SpeedTest.forget(app, id)
        _state.update { it.copy(speeds = it.speeds - id) }
        if (state.settings.startupId == id) changeSettings { it.copy(startupId = null, boot = false) }
        refresh()
    }

    fun rawConfig(serverId: String): String = ServerStore.displayConfig(app, serverId)

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

    /**
     * A file or text from a phone or a link. A backup waits for its passphrase; anything else
     * opens the review. Returns what is wrong, for the phone to show, or null when it arrived.
     */
    fun receive(name: String, bytes: ByteArray): String? {
        if (Backup.isBackup(bytes)) {
            _state.update { it.copy(pendingRestore = bytes) }
            return null
        }
        if (Certificates.isPkcs12(name, bytes)) {
            _state.update { it.copy(pendingP12 = bytes) }
            return null
        }
        val review = ServerStore.review(name.ifBlank { "pasted.ovpn" }, bytes)
        if (review.problem != null) return review.problem
        _state.update { it.copy(review = review) }
        return null
    }

    /** Downloads a profile from [url], with a login when the link needs one. Returns what went wrong, or null. */
    suspend fun download(url: String, username: String?, password: String?): String? = withContext(Dispatchers.IO) {
        val clean = url.trim()
        // Only https: a profile holds keys, and Android refuses plain http anyway.
        if (clean.startsWith("http://")) return@withContext "Use an https:// link. ${APP_NAME} won’t download a profile without encryption."
        if (!clean.startsWith("https://")) return@withContext "The link must start with https://"
        runCatching {
            val headers = if (username.isNullOrEmpty()) emptyMap() else mapOf(
                "Authorization" to "Basic " + android.util.Base64.encodeToString("$username:${password.orEmpty()}".toByteArray(), android.util.Base64.NO_WRAP),
            )
            val r = Http.get(clean, headers, timeoutMs = 10_000)
            when (r.code) {
                200 -> r.body ?: ByteArray(0)
                401, 403 -> return@withContext "The link needs a username and password, or they are wrong."
                404 -> return@withContext "Nothing was found at that link."
                else -> return@withContext "The server answered ${r.code}."
            }
        }.fold(
            onSuccess = { bytes ->
                val name = clean.substringAfterLast('/').substringBefore('?').ifBlank { "download.ovpn" }
                withContext(Dispatchers.Main) { receive(name, bytes) }
            },
            onFailure = { "${APP_NAME} could not download it: ${it.message ?: "no answer"}." },
        )
    }

    /** Opens the waiting .p12 with [password]; null when it opens, or what is wrong. */
    fun checkP12(password: String): String? {
        val bytes = _state.value.pendingP12 ?: return "There is no certificate waiting."
        return (Certificates.readPkcs12(bytes, password) as? Certificates.Read.Problem)?.message
    }

    /** Puts the waiting .p12's certificate and key into profile [id]'s file. Returns the problem, or null. */
    fun attachP12(password: String, id: String): String? {
        val bytes = _state.value.pendingP12 ?: return "There is no certificate waiting."
        val bundle = when (val r = Certificates.readPkcs12(bytes, password)) {
            is Certificates.Read.Done -> r.bundle
            is Certificates.Read.Problem -> return r.message
        }
        val c = _state.value.connection(id) ?: return "That profile is gone."
        val config = ServerStore.rawConfig(app, c.serverId) ?: return "The profile's file is missing."
        ServerStore.replaceConfig(app, c.serverId, Certificates.attach(config, bundle))
        // The file's own certificate is used again.
        ConnectionStore.update(app, id) { it.copy(keyAlias = null) }
        _state.update { it.copy(pendingP12 = null) }
        refresh()
        return null
    }

    /** Moves the waiting .p12's key into Android's keystore. Returns the alias, or null. */
    fun keepP12(password: String): String? {
        val bytes = _state.value.pendingP12 ?: return null
        val alias = Certificates.keepInKeystore(bytes, password) ?: return null
        _state.update { it.copy(pendingP12 = null) }
        return alias
    }

    fun cancelP12() {
        _state.update { it.copy(pendingP12 = null) }
    }

    /** Uses a certificate installed on the TV for profile [id], or the file's own again with null. */
    fun setKeyAlias(id: String, alias: String?) {
        val before = _state.value.connection(id)?.keyAlias
        changeConnection(id) { it.copy(keyAlias = alias) }
        if (before != null && before != alias) releaseKey(before)
    }

    /** Forgets a key Tunnel kept once no profile uses it. */
    private fun releaseKey(alias: String) {
        if (ConnectionStore.list(app).none { it.keyAlias == alias }) Certificates.forget(alias)
    }

    fun cancelRestore() {
        _state.update { it.copy(pendingRestore = null) }
    }

    /** Restores the waiting backup. Returns a line for the user. */
    suspend fun restore(passphrase: String): Backup.Restored {
        val bytes = _state.value.pendingRestore ?: return Backup.Restored.Problem("There is no backup to restore.")
        val result = withContext(Dispatchers.Default) { Backup.restore(app, bytes, passphrase) }
        if (result is Backup.Restored.Done) {
            _state.update { it.copy(pendingRestore = null, settings = SettingsStore.load(app)) }
            refresh()
        }
        return result
    }

    fun setBackupPassphrase(passphrase: String?) {
        CredentialStore.putSecret(app, BACKUP_SECRET, passphrase?.takeIf { it.isNotEmpty() })
        refresh()
    }

    /** The encrypted backup to send to a phone, or null until a passphrase is set. */
    suspend fun backupFile(): ByteArray? {
        val passphrase = CredentialStore.secret(app, BACKUP_SECRET) ?: return null
        return withContext(Dispatchers.Default) { Backup.create(app, passphrase) }
    }

    /** A profile's .ovpn file to send to a phone. The saved password is not in it. */
    fun profileFile(id: String): ByteArray? {
        val c = _state.value.connection(id) ?: return null
        return ServerStore.effectiveConfig(app, c.serverId)?.toByteArray()
    }

    fun logText(): String = _state.value.log.joinToString("\n") { "${it.time}  ${it.text}" }

    fun setPin(pin: String?) {
        SettingsStore.setPin(app, pin)
        if (pin == null) changeSettings { it.copy(pinMode = "Off") }
        refresh()
    }

    fun checkPin(pin: String): Boolean = SettingsStore.checkPin(app, pin)

    /** Saves the reviewed file as a new profile and returns its id. */
    fun saveReview(): String? {
        val review = _state.value.review ?: return null
        if (review.problem != null) return null
        val server = ServerStore.add(app, review.text)
        val connection = ConnectionStore.add(app, review.name, server.id)
        if (review.username.isNotEmpty()) CredentialStore.save(app, connection.id, review.username, review.password)
        _state.update { it.copy(review = null) }
        refresh()
        if (_state.value.activeId == null) setActive(connection.id)
        return connection.id
    }

    // ---- Settings ---------------------------------------------------------------

    fun changeSettings(change: (AppSettings) -> AppSettings) {
        val next = change(_state.value.settings)
        SettingsStore.save(app, next)
        _state.update { it.copy(settings = next) }
    }

    fun clearLog() = link.clearLog()

    override fun onCleared() {
        link.close()
    }

    /** Deletes every profile, password and setting. */
    fun resetAll() {
        disconnect()
        ConnectionStore.list(app).mapNotNull { it.keyAlias }.distinct().forEach(Certificates::forget)
        ConnectionStore.clear(app)
        ServerStore.clear(app)
        CredentialStore.clear(app)
        SpeedTest.forget(app, null)
        _state.update { it.copy(speeds = emptyMap()) }
        changeSettings { AppSettings() }
        VpnController.update(VpnStatus.Disconnected)
        refresh()
    }

    // ---- Loading ------------------------------------------------------------------

    private fun setActive(id: String) {
        _state.update { it.copy(activeId = id) }
        SettingsStore.setActiveConnection(app, id)
    }

    /** Re-reads profiles, servers and logins, and keeps the active profile pointing at one that exists. */
    private fun refresh(preferActive: String? = null) {
        val connections = ConnectionStore.list(app)
        val servers = ServerStore.list(app).associateBy { it.id }
        val logins = connections.associate { c ->
            val login = CredentialStore.load(app, c.id)
            c.id to Login(login.username, login.password.isNotEmpty())
        }
        val infos = servers.keys.mapNotNull { id -> ServerStore.info(app, id)?.let { id to it } }.toMap()
        val hasPassphrase = CredentialStore.secret(app, BACKUP_SECRET) != null
        val hasPin = SettingsStore.hasPin(app)
        _state.update { s ->
            val wanted = preferActive ?: s.activeId
            val active = connections.firstOrNull { it.id == wanted } ?: connections.firstOrNull()
            s.copy(
                connections = connections, servers = servers, logins = logins, infos = infos, activeId = active?.id,
                hasBackupPassphrase = hasPassphrase, hasPin = hasPin,
            )
        }
        SettingsStore.setActiveConnection(app, _state.value.activeId)
    }

    companion object {
        private const val PAUSE_MILLIS = 15 * 60 * 1000L
        private const val UPDATE_APK = "update.apk"
        private const val BACKUP_SECRET = "backup"
    }
}
