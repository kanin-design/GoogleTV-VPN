package dev.shieldvpn.control

import android.app.Application
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.Features
import dev.shieldvpn.vpn.AppSettings
import dev.shieldvpn.vpn.Backup
import dev.shieldvpn.vpn.Certificates
import dev.shieldvpn.vpn.ConfigInfo
import dev.shieldvpn.vpn.Connection
import dev.shieldvpn.vpn.ConnectionStore
import dev.shieldvpn.vpn.CredentialStore
import dev.shieldvpn.vpn.Http
import dev.shieldvpn.vpn.ImportReview
import dev.shieldvpn.vpn.Login
import dev.shieldvpn.vpn.Protocol
import dev.shieldvpn.vpn.Server
import dev.shieldvpn.vpn.ServerStore
import dev.shieldvpn.vpn.SettingsStore
import dev.shieldvpn.vpn.SpeedTest
import dev.shieldvpn.vpn.Tunnels
import dev.shieldvpn.vpn.VpnController
import dev.shieldvpn.vpn.VpnKind
import dev.shieldvpn.vpn.VpnLink
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.WireGuardFile
import dev.shieldvpn.vpn.connectionId
import dev.shieldvpn.vpn.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the stores hold, as everything that shows or changes it sees it. */
data class CoreState(
    val connections: List<Connection> = emptyList(),
    val servers: Map<String, Server> = emptyMap(),
    val logins: Map<String, Login> = emptyMap(),
    val infos: Map<String, ConfigInfo> = emptyMap(),
    /** The profile Connect shows. */
    val activeId: String? = null,
    val settings: AppSettings = AppSettings(),
    val hasBackupPassphrase: Boolean = false,
    val hasPin: Boolean = false,
    /** When the last backup was downloaded, and of how many profiles. */
    val lastBackup: Pair<Long, Int>? = null,
    val speeds: Map<String, SpeedTest.Result> = emptyMap(),
    /** Raised by every change, so a phone can tell when what it saw is out of date. */
    val revision: Long = 0,
)

/**
 * Every task on the app's data, in one place: the TV's screens, the phone pages, automation and
 * the boot and Always-on receivers all come here. It has no screens; what a screen shows next is
 * the screen's own business.
 *
 * Main thread only. Each change re-reads the stores and tells [listen]ers before it returns, so a
 * caller that reads the state straight after a change sees it.
 */
object Control {

    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(CoreState())
    val state: StateFlow<CoreState> = _state.asStateFlow()
    private val listeners = mutableListOf<(CoreState) -> Unit>()

    // The tunnel's state, from the VPN's own process, while something here needs it.
    private var link: VpnLink.Client? = null
    private var linkUsers = 0

    private const val BACKUP_SECRET = "backup"
    private const val PAUSE_MILLIS = 15 * 60 * 1000L

    /** Called once by the Application; later calls do nothing. */
    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application
        // An Apply and test from a phone that was cut off: the settings before it come back.
        val cutOff = Snapshot.restore(app)
        _state.value = _state.value.copy(settings = SettingsStore.load(app), speeds = SpeedTest.load(app))
        SettingsStore.syncReach(app, _state.value.settings.phoneReach)
        // Wrong PINs are counted across restarts, from the TV and phones alike.
        val pinPrefs = app.getSharedPreferences("shieldvpn_pin", android.content.Context.MODE_PRIVATE)
        PinTries.misses = pinPrefs.getInt("misses", 0)
        PinTries.waitUntil = pinPrefs.getLong("waitUntil", 0)
        PinTries.keep = { misses, until -> pinPrefs.edit().putInt("misses", misses).putLong("waitUntil", until).apply() }
        refresh(preferActive = SettingsStore.activeConnection(app))
        if (cutOff) scope.launch { note("A change from a phone was cut off part way. The settings from before it are back.") }
        scope.launch { VpnController.status.collect(::onStatus) }
    }

    /** Calls [listener] at once and after every change, on the main thread. Returns how to stop. */
    fun listen(listener: (CoreState) -> Unit): () -> Unit {
        listeners += listener
        listener(_state.value)
        return { listeners -= listener }
    }

    /**
     * Follows the tunnel through the VPN's process while at least one user (the screens, the phone
     * panel) needs it. While nobody does, nothing is bound, and an idle VPN process can go.
     */
    fun attachLink() {
        if (linkUsers++ != 0) return
        link = VpnLink.Client(app).also { c ->
            c.open()
            unsent.forEach(c::log)
            unsent.clear()
        }
    }

    // Lines noted while nothing follows the VPN's process, such as the cut-off note at start: its
    // log replaces this process's copy when the link opens, so they are sent there then.
    private val unsent = mutableListOf<String>()

    fun detachLink() {
        if (linkUsers == 0) return
        if (--linkUsers == 0) {
            link?.close()
            link = null
        }
    }

    fun clearLog() {
        link?.clearLog()
    }

    /** A line in the connection log, kept by the VPN's process with the rest: "Pixel 8 set …". */
    fun note(text: String) {
        val l = link
        if (l == null && unsent.size < 20) unsent += text
        if (l?.log(text) != true) VpnController.log(text)
    }

    // ---- The tunnel ---------------------------------------------------------------

    /**
     * Connects [id]. A [login] typed in the sign-in sheet is saved first when [remember] is on,
     * or used just this once. Returns false when the profile still needs a login. The caller has
     * made sure Android allows the VPN.
     */
    fun connect(id: String, login: CredentialStore.Credentials? = null, remember: Boolean = true): Boolean {
        if (login != null && remember) {
            CredentialStore.save(app, id, login.username.trim(), login.password)
            refresh()
        }
        // It becomes the profile Connect shows once it starts, or fails: a sign-in sheet that is
        // closed must leave Connect on the tunnel that is still running.
        return when (val plan = Tunnels.plan(app, id, login?.takeIf { !remember })) {
            is Tunnels.Plan.Ready -> {
                setActive(id)
                Tunnels.start(app, plan)
                true
            }
            is Tunnels.Plan.NeedsLogin -> false
            is Tunnels.Plan.Fails -> {
                if (!VpnController.status.value.isActive) setActive(id)
                failEarly(id, plan.message)
                true
            }
        }
    }

    fun disconnect() = Tunnels.stop(app)

    /** Turns the tunnel off for 15 minutes. The service brings it back, even with the app closed. */
    fun pause() = Tunnels.pause(app, System.currentTimeMillis() + PAUSE_MILLIS)

    fun pauseMore() {
        val until = VpnController.pausedUntil.value ?: return
        Tunnels.pause(app, until + PAUSE_MILLIS)
    }

    fun resume() {
        _state.value.activeId?.let { connect(it) }
    }

    /** Connects the tunnel that is up again, so changed settings take effect. Paused, they apply when it comes back. */
    fun reconnect() {
        val status = VpnController.status.value
        if (VpnController.pausedUntil.value != null) return
        status.connectionId?.takeIf { status.isActive }?.let { connect(it) }
    }

    /** Why [id] can't connect at all, before anything starts, or null. */
    fun connectProblem(id: String): String? = (Tunnels.plan(app, id) as? Tunnels.Plan.Fails)?.message

    // ---- Apply and test ------------------------------------------------------------------

    fun snapshot() = Snapshot.take(app)

    fun keepSnapshot() = Snapshot.drop(app)

    /** Puts the stores back as they were at [snapshot], and reads them again. */
    fun rollBack() {
        if (!Snapshot.restore(app)) return
        publicKeys.clear()
        val settings = SettingsStore.load(app)
        SettingsStore.syncReach(app, settings.phoneReach)
        publish(_state.value.copy(settings = settings))
        refresh()
        applyKillSwitch(settings)
    }

    fun peekBackup(bytes: ByteArray, passphrase: String, replace: Boolean): Backup.Peeked = Backup.peek(app, bytes, passphrase, replace)

    /** A connect that fails before any tunnel starts. It must not disturb a tunnel that is still up. */
    private fun failEarly(id: String, message: String) {
        if (VpnController.status.value.isActive) {
            ConnectionStore.update(app, id) { it.copy(lastError = message) }
            refresh()
        } else {
            VpnController.update(VpnStatus.Failed(id, message))
        }
    }

    private fun onStatus(status: VpnStatus) {
        // A tunnel started elsewhere (at boot, by another app, Always-on) is the one Connect shows.
        status.connectionId
            ?.takeIf { status.isActive && it != _state.value.activeId && connection(it) != null }
            ?.let(::setActive)
        // Each profile remembers why it last failed, until it connects again.
        when (status) {
            is VpnStatus.Failed -> status.connectionId?.let { id ->
                if (connection(id)?.lastError != status.message) {
                    ConnectionStore.update(app, id) { it.copy(lastError = status.message) }
                    refresh()
                }
            }
            // The service has recorded it as used, or as last up when a tunnel ended; read that back.
            is VpnStatus.Connected, is VpnStatus.Connecting, VpnStatus.Disconnected -> refresh()
            else -> Unit
        }
    }

    fun setActive(id: String) {
        if (_state.value.activeId == id) return
        SettingsStore.setActiveConnection(app, id)
        publish(_state.value.copy(activeId = id))
    }

    /** Keeps [r] as the last speed test of profile [key], or of the connection without the VPN. */
    fun saveSpeed(key: String, r: SpeedTest.Result) {
        SpeedTest.save(app, key, r)
        publish(_state.value.copy(speeds = _state.value.speeds + (key to r)))
    }

    // ---- Profiles -------------------------------------------------------------------

    fun connection(id: String): Connection? = _state.value.connections.firstOrNull { it.id == id }

    fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        ConnectionStore.update(app, id) { it.copy(name = clean) }
        refresh()
    }

    /** A null [password] keeps the saved one, so the username alone can change. */
    fun saveLogin(id: String, username: String, password: String?) {
        CredentialStore.save(app, id, username.trim(), password ?: CredentialStore.load(app, id).password)
        refresh()
    }

    fun forgetPassword(id: String) {
        CredentialStore.save(app, id, CredentialStore.username(app, id), "")
        // It can't connect by itself without it.
        if (_state.value.settings.startupId == id) dropStartup(id)
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
        val c = connection(id) ?: return
        val server = ownServer(id) ?: return
        val protocol = when (value) {
            "UDP" -> Protocol.UDP
            "TCP" -> Protocol.TCP
            else -> _state.value.infos[c.serverId]?.fileProtocol ?: server.protocol
        }
        ServerStore.update(app, server.copy(protocol = protocol))
        refresh()
    }

    fun setKillMode(id: String, value: String) {
        changeConnection(id) { it.copy(killMode = value) }
        if (VpnController.status.value.connectionId == id) {
            link?.setKillSwitch(when (value) {
                "Always on" -> true
                "Off" -> false
                else -> _state.value.settings.killSwitch
            })
        }
    }

    fun setDnsMode(id: String, value: String) = changeConnection(id) { it.copy(dnsMode = value) }

    fun setOrder(id: String, value: String) = changeConnection(id) { it.copy(order = value) }

    fun setPinned(id: String, pinned: Boolean) = changeConnection(id) { it.copy(pinned = pinned) }

    /** The icon and colour shown for the profile; null for both picks them from its name again. */
    fun setLook(id: String, icon: String?, colour: Int?) = changeConnection(id) { it.copy(lookIcon = icon, lookColour = colour) }

    private fun changeConnection(id: String, change: (Connection) -> Connection) {
        ConnectionStore.update(app, id, change)
        refresh()
    }

    /** Makes a copy right after the original, with the same login. Returns the copy. */
    fun duplicate(id: String): Connection? {
        val source = connection(id) ?: return null
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
        val connection = connection(id) ?: return
        val status = VpnController.status.value
        if (status.connectionId == id) {
            if (status.isActive) Tunnels.stop(app) else VpnController.update(VpnStatus.Disconnected)
        }
        ConnectionStore.delete(app, id)
        CredentialStore.remove(app, id)
        publicKeys.remove(id)
        // A server goes with its last profile, and so does a key the app kept for it.
        if (ConnectionStore.list(app).none { it.serverId == connection.serverId }) ServerStore.delete(app, connection.serverId)
        connection.keyAlias?.let(::releaseKey)
        SpeedTest.forget(app, id)
        publish(_state.value.copy(speeds = _state.value.speeds - id))
        if (_state.value.settings.startupId == id) dropStartup(id)
        refresh()
    }

    fun rawConfig(serverId: String): String = ServerStore.displayConfig(app, serverId)

    /**
     * A profile's file to send to a phone. An OpenVPN file goes without the saved password; a
     * WireGuard file has its key put back, as the file is no use on another device without it.
     */
    fun profileFile(id: String): ByteArray? {
        val c = connection(id) ?: return null
        val text = ServerStore.effectiveConfig(app, c.serverId) ?: return null
        if (_state.value.servers[c.serverId]?.kind != VpnKind.WireGuard) return text.toByteArray()
        val keys = CredentialStore.wireGuard(app, id) ?: return null
        return dev.shieldvpn.vpn.WireGuardFile.withSecrets(text, keys).toByteArray()
    }

    /** Saves a reviewed file as a new profile and returns its id. Connect shows it unless a tunnel is up. */
    fun addProfile(review: ImportReview): String? {
        if (review.problem != null) return null
        val server = ServerStore.add(app, review.text)
        val connection = ConnectionStore.add(app, review.name, server.id)
        if (review.username.isNotEmpty()) CredentialStore.save(app, connection.id, review.username, review.password)
        review.wgSecrets?.let { CredentialStore.saveWireGuard(app, connection.id, it) }
        refresh()
        val live = VpnController.status.value.isActive || VpnController.pausedUntil.value != null
        if (_state.value.activeId == null || !live) setActive(connection.id)
        return connection.id
    }

    // ---- Startup --------------------------------------------------------------------

    /**
     * The profile that connects when the device starts, or null for none. The one it replaces is
     * remembered, so deleting the new one gives the slot back.
     */
    fun setStartup(id: String?) = changeSettings { s ->
        val before = s.startupId?.takeIf { s.boot && it != id }
        s.copy(startupId = id, boot = id != null, startupBefore = before ?: s.startupBefore?.takeIf { it != id })
    }

    /** The profile that would take the slot back if [id] lost it, or null. */
    fun startupFallback(id: String): Connection? {
        val s = _state.value.settings
        return s.startupBefore?.takeIf { it != id }?.let(::connection)
            ?.takeIf { c -> _state.value.infos[c.serverId]?.needsLogin != true || _state.value.logins[c.id]?.hasPassword == true }
    }

    /** [id] can't be the startup profile any more: the one before it gets the slot back, if it still can. */
    private fun dropStartup(id: String) {
        val back = startupFallback(id)
        changeSettings { it.copy(startupId = back?.id, boot = back != null, startupBefore = null) }
    }

    // ---- Certificates -----------------------------------------------------------------

    /** Opens the .p12 in [bytes] with [password]; null when it opens, or what is wrong. The key derivation runs off the main thread. */
    suspend fun checkP12(bytes: ByteArray, password: String): String? =
        withContext(Dispatchers.Default) { (Certificates.readPkcs12(bytes, password) as? Certificates.Read.Problem)?.message }

    /** Puts the certificate and key of the .p12 in [bytes] into profile [id]'s file. Returns the problem, or null. */
    suspend fun attachP12(bytes: ByteArray, password: String, id: String): String? {
        val bundle = when (val r = withContext(Dispatchers.Default) { Certificates.readPkcs12(bytes, password) }) {
            is Certificates.Read.Done -> r.bundle
            is Certificates.Read.Problem -> return r.message
        }
        val c = connection(id) ?: return "That profile is gone."
        val server = ownServer(id) ?: return "The profile's file is missing."
        val config = ServerStore.rawConfig(app, server.id) ?: return "The profile's file is missing."
        ServerStore.replaceConfig(app, server.id, Certificates.attach(config, bundle))
        // The file's own certificate is used again.
        val before = c.keyAlias
        ConnectionStore.update(app, id) { it.copy(keyAlias = null) }
        before?.let(::releaseKey)
        refresh()
        return null
    }

    /** Moves the key of the .p12 in [bytes] into Android's keystore. Returns the alias, or null. */
    suspend fun keepP12(bytes: ByteArray, password: String): String? =
        withContext(Dispatchers.Default) { Certificates.keepInKeystore(bytes, password) }

    /** Uses a certificate installed on the device for profile [id], or the file's own again with null. */
    fun setKeyAlias(id: String, alias: String?) {
        val before = connection(id)?.keyAlias
        changeConnection(id) { it.copy(keyAlias = alias) }
        if (before != null && before != alias) releaseKey(before)
    }

    /** Forgets a key the app kept once no profile uses it. */
    private fun releaseKey(alias: String) {
        if (ConnectionStore.list(app).none { it.keyAlias == alias }) Certificates.forget(alias)
    }

    /**
     * The profiles a .p12 belongs to: those whose CA signed its certificate. Read with [password],
     * off the main thread. Empty when it can't be opened or no profile's CA matches.
     */
    suspend fun profilesForCertificate(bytes: ByteArray, password: String): List<Connection> {
        val bundle = (withContext(Dispatchers.Default) { Certificates.readPkcs12(bytes, password) } as? Certificates.Read.Done)?.bundle ?: return emptyList()
        val issuer = runCatching { javax.security.auth.x500.X500Principal(bundle.issuer).name }.getOrDefault(bundle.issuer)
        return _state.value.connections.filter { c ->
            val config = ServerStore.rawConfig(app, c.serverId) ?: return@filter false
            issuer.isNotEmpty() && issuer in Certificates.caSubjects(config)
        }
    }

    /**
     * A profile already here for the same server as [review]: an OpenVPN file with the same CA and
     * first server, or a WireGuard file with the same server key and endpoint. Null when none is.
     */
    fun sameServer(review: ImportReview): Connection? {
        if (review.problem != null) return null
        val s = _state.value
        return s.connections.firstOrNull { c ->
            val server = s.servers[c.serverId] ?: return@firstOrNull false
            val raw = ServerStore.rawConfig(app, c.serverId) ?: return@firstOrNull false
            if (review.kind == VpnKind.WireGuard) {
                server.kind == VpnKind.WireGuard && WireGuardFile.endpoint(raw) == (review.host to review.port) &&
                    peerKeys(raw) == peerKeys(review.text)
            } else {
                server.kind == VpnKind.OpenVpn && s.infos[c.serverId]?.let { it.fileHost == review.host && it.filePort == review.port } == true &&
                    Certificates.caSubjects(raw) == Certificates.caSubjects(review.text)
            }
        }
    }

    private fun peerKeys(text: String): List<String> =
        text.lines().map { it.substringBefore('#').trim() }.filter { it.lowercase().startsWith("publickey") }.map { it.substringAfter('=').trim() }

    /** [review] is the file profile [id] has already, with the same keys: updating would change nothing. */
    fun hasFile(id: String, review: ImportReview): Boolean {
        val c = connection(id) ?: return false
        val raw = ServerStore.rawConfig(app, c.serverId) ?: return false
        if (raw.trim() != review.text.trim()) return false
        if (review.wgSecrets != null && review.wgSecrets != CredentialStore.wireGuard(app, c.id)) return false
        return review.username.isEmpty() || review.username == _state.value.logins[c.id]?.username && review.password.isEmpty()
    }

    /**
     * Puts [review]'s file in place of profile [id]'s, keeping its name, look and settings; a login or
     * key in the review replaces the saved one. Advanced's server change goes back to the file's.
     */
    fun updateProfile(id: String, review: ImportReview): Boolean {
        val c = connection(id) ?: return false
        val server = ownServer(id) ?: return false
        if (review.problem != null) return false
        ServerStore.replaceConfig(app, server.id, review.text)
        ServerStore.resetToFile(app, server.id)
        if (review.username.isNotEmpty()) CredentialStore.save(app, c.id, review.username, review.password)
        review.wgSecrets?.let { CredentialStore.saveWireGuard(app, c.id, it) }
        publicKeys.remove(id)
        ConnectionStore.update(app, id) { it.copy(lastError = null) }
        refresh()
        return true
    }

    // ---- Backup -----------------------------------------------------------------------

    /** Restores the backup in [bytes]; with [replace], profiles with the same name are replaced. */
    suspend fun restore(bytes: ByteArray, passphrase: String, replace: Boolean, withSettings: Boolean = true): Backup.Restored {
        val before = _state.value.settings
        val result = withContext(Dispatchers.Default) { Backup.restore(app, bytes, passphrase, replace, withSettings) }
        if (result is Backup.Restored.Done) {
            val after = SettingsStore.load(app)
            publish(_state.value.copy(settings = after))
            refresh()
            // The kill switch holds at once on the tunnel that is up, as when it is switched by hand.
            if (after.killSwitch != before.killSwitch) applyKillSwitch(after)
        }
        return result
    }

    /** A phone has downloaded a backup: Settings › Backup says when, and of how many profiles. */
    fun backupDownloaded() {
        SettingsStore.setLastBackup(app, System.currentTimeMillis(), ConnectionStore.list(app).size)
        refresh()
    }

    /** [passphrase] is the one set now, compared in constant time. */
    fun checkBackupPassphrase(passphrase: String): Boolean {
        val set = CredentialStore.secret(app, BACKUP_SECRET) ?: return false
        return java.security.MessageDigest.isEqual(set.toByteArray(), passphrase.toByteArray())
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

    // ---- Links ------------------------------------------------------------------------

    sealed interface Downloaded {
        data class File(val name: String, val bytes: ByteArray) : Downloaded
        data class Problem(val message: String) : Downloaded
    }

    /** Downloads a file from [url], with a login when the link needs one. */
    suspend fun download(url: String, username: String?, password: String?): Downloaded = withContext(Dispatchers.IO) {
        val clean = url.trim()
        // Only https: a profile holds keys, and Android refuses plain http anyway.
        if (clean.startsWith("http://")) return@withContext Downloaded.Problem("Use an https:// link. ${APP_NAME} won’t download a profile without encryption.")
        if (!clean.startsWith("https://")) return@withContext Downloaded.Problem("The link must start with https://")
        runCatching {
            val headers = if (username.isNullOrEmpty()) emptyMap() else mapOf(
                "Authorization" to "Basic " + android.util.Base64.encodeToString("$username:${password.orEmpty()}".toByteArray(), android.util.Base64.NO_WRAP),
            )
            val r = Http.get(clean, headers, timeoutMs = 10_000)
            when (r.code) {
                200 -> r.body ?: ByteArray(0)
                401, 403 -> return@withContext Downloaded.Problem("The link needs a username and password, or they are wrong.")
                404 -> return@withContext Downloaded.Problem("Nothing was found at that link.")
                else -> return@withContext Downloaded.Problem("The server answered ${r.code}.")
            }
        }.fold(
            onSuccess = { bytes -> Downloaded.File(clean.substringAfterLast('/').substringBefore('?').ifBlank { "download" }, bytes) },
            onFailure = { Downloaded.Problem(linkProblem(clean, it)) },
        )
    }

    /** Why a link didn't download, in plain words instead of the exception's. */
    private fun linkProblem(url: String, e: Throwable): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "The server"
        return when (e) {
            is java.net.UnknownHostException -> "There is no server called $host. Check the link."
            is java.net.SocketTimeoutException, is java.net.ConnectException -> "$host didn’t answer. Check the link, or try again later."
            is javax.net.ssl.SSLException -> "$host’s certificate isn’t trusted, so ${APP_NAME} didn’t download it."
            else -> "The download from $host failed. Check the link and try again."
        }
    }

    // ---- Settings and the PIN -----------------------------------------------------------

    fun changeSettings(change: (AppSettings) -> AppSettings) {
        val before = _state.value.settings
        val next = change(before)
        SettingsStore.save(app, next)
        publish(_state.value.copy(settings = next, revision = _state.value.revision + 1))
        if (next.killSwitch != before.killSwitch) applyKillSwitch(next)
        if (next.phoneReach != before.phoneReach) remoteChanged()
    }

    /** Phone access or the paired phones changed: the VPN's service keeps the panel's server up, or lets it go. */
    fun remoteChanged() {
        val s = VpnController.status.value
        if (!s.isActive && VpnController.pausedUntil.value == null) return
        runCatching { app.startService(android.content.Intent(app, dev.shieldvpn.vpn.ShieldVpnService::class.java).setAction(dev.shieldvpn.vpn.ShieldVpnService.ACTION_REMOTE)) }
    }

    /** The kill switch takes effect at once on the tunnel that is up, for a profile that follows the app's setting. */
    private fun applyKillSwitch(settings: AppSettings) {
        val id = VpnController.status.value.connectionId ?: return
        val c = connection(id) ?: return
        if (c.killMode != Connection.APP_SETTING) return
        link?.setKillSwitch(settings.killSwitch)
    }

    fun setPin(pin: String?) {
        SettingsStore.setPin(app, pin)
        if (pin == null) changeSettings { it.copy(pinMode = "Off") }
        refresh()
    }

    fun checkPin(pin: String): Boolean = SettingsStore.checkPin(app, pin)

    /** Deletes every profile, password and setting. */
    fun resetAll() {
        disconnect()
        ConnectionStore.list(app).mapNotNull { it.keyAlias }.distinct().forEach(Certificates::forget)
        ConnectionStore.clear(app)
        ServerStore.clear(app)
        CredentialStore.clear(app)
        SpeedTest.forget(app, null)
        SettingsStore.setLastBackup(app, 0, 0)
        publish(_state.value.copy(speeds = emptyMap()))
        changeSettings { AppSettings() }
        VpnController.update(VpnStatus.Disconnected)
        refresh()
    }

    // ---- Loading ------------------------------------------------------------------------

    /** Re-reads profiles, servers and logins, and keeps the active profile pointing at one that exists. */
    fun refresh(preferActive: String? = null) {
        val servers = ServerStore.list(app).associateBy { it.id }
        // A WireGuard profile stays stored in a build without WireGuard, but isn't shown.
        val connections = ConnectionStore.list(app).filter { Features.WIREGUARD || servers[it.serverId]?.kind != VpnKind.WireGuard }
        // Read without decrypting: this runs after nearly every change. A WireGuard key is read once.
        val logins = connections.associate { c ->
            val publicKey = if (servers[c.serverId]?.kind == VpnKind.WireGuard) publicKeys.getOrPut(c.id) { wireGuardPublicKey(c.id) } else null
            c.id to Login(CredentialStore.username(app, c.id), CredentialStore.hasPassword(app, c.id), publicKey)
        }
        val infos = servers.keys.mapNotNull { id -> ServerStore.info(app, id)?.let { id to it } }.toMap()
        val s = _state.value
        val wanted = preferActive ?: s.activeId
        val active = connections.firstOrNull { it.id == wanted } ?: connections.firstOrNull()
        val next = s.copy(
            connections = connections, servers = servers, logins = logins, infos = infos, activeId = active?.id,
            hasBackupPassphrase = CredentialStore.hasSecret(app, BACKUP_SECRET), hasPin = SettingsStore.hasPin(app),
            lastBackup = SettingsStore.lastBackup(app), revision = s.revision + 1,
        )
        if (next.activeId != s.activeId) SettingsStore.setActiveConnection(app, next.activeId)
        publish(next)
    }

    // WireGuard profiles' public keys by profile id: deriving one decrypts the private key.
    private val publicKeys = HashMap<String, String?>()

    private fun wireGuardPublicKey(id: String): String? = CredentialStore.wireGuard(app, id)?.let { keys ->
        runCatching { com.wireguard.crypto.KeyPair(com.wireguard.crypto.Key.fromBase64(keys.privateKey)).publicKey.toBase64() }.getOrNull()
    }

    private fun publish(next: CoreState) {
        _state.value = next
        listeners.toList().forEach { it(next) }
    }
}
