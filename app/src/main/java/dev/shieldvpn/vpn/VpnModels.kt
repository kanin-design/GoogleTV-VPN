package dev.shieldvpn.vpn

/** Where a tunnel goes. Read from the .ovpn file; the user can change it in Advanced. */
data class Server(
    val id: String,
    val host: String,
    val port: Int,
    val protocol: Protocol,
) {
    val address: String get() = "$host:$port"
}

enum class Protocol(val label: String, val token: String) {
    UDP("UDP", "udp"),
    TCP("TCP", "tcp");

    val other: Protocol get() = if (this == UDP) TCP else UDP
}

/** A profile: what the user connects with. Its login is kept in [CredentialStore]. */
data class Connection(
    val id: String,
    val name: String,
    val serverId: String,
    /** Why the last attempt failed, until a later attempt connects. */
    val lastError: String? = null,
    /** Shown first in Quick connect. */
    val pinned: Boolean = false,
    /** When it last connected, in epoch milliseconds. */
    val lastUsed: Long? = null,
    /** Kill switch for this profile: "App setting", "Always on" or "Off". */
    val killMode: String = APP_SETTING,
    /** DNS for this profile: "App setting" or "Server only". */
    val dnsMode: String = APP_SETTING,
    /** Server order when the file lists several: "In order" or "Random". */
    val order: String = "In order",
    /** The icon and colour the user picked, or null to pick them from the name. */
    val lookIcon: String? = null,
    val lookColour: Int? = null,
    /** A client certificate installed on the TV, used instead of the one in the file. */
    val keyAlias: String? = null,
) {
    companion object {
        const val APP_SETTING = "App setting"
    }
}

sealed interface VpnStatus {
    data object Disconnected : VpnStatus
    data class Connecting(val connectionId: String) : VpnStatus
    data class Connected(val connectionId: String, val sinceMillis: Long) : VpnStatus

    /** The tunnel dropped and the kill switch is holding traffic back. */
    data class Blocked(val connectionId: String) : VpnStatus
    data class Failed(val connectionId: String?, val message: String) : VpnStatus
}

/** The connection a status is about, if any. */
val VpnStatus.connectionId: String?
    get() = when (this) {
        VpnStatus.Disconnected -> null
        is VpnStatus.Connecting -> connectionId
        is VpnStatus.Connected -> connectionId
        is VpnStatus.Blocked -> connectionId
        is VpnStatus.Failed -> connectionId
    }

/** True while a tunnel exists or is being set up. */
val VpnStatus.isActive: Boolean
    get() = this is VpnStatus.Connecting || this is VpnStatus.Connected || this is VpnStatus.Blocked

/** What the screens may show about a saved login. The password itself stays in [CredentialStore]. */
data class Login(val username: String, val hasPassword: Boolean)

/** The client certificate inside an .ovpn file. */
data class CertInfo(
    val subject: String,
    val issuer: String,
    val validUntil: String,
    val key: String,
    val fingerprint: String,
)

/** What an .ovpn file says, for the screens. */
data class ConfigInfo(
    val cipher: String?,
    val tls: String?,
    val needsLogin: Boolean,
    val clientCert: Boolean,
    val cert: CertInfo?,
    val fileHost: String?,
    val filePort: Int?,
    val fileProtocol: Protocol?,
    val remotes: Int,
    val warnings: List<String>,
    /** The file has the server's CA certificate, so Tunnel can check the server. */
    val hasCa: Boolean = true,
)

/** The interface Android set up for the tunnel. */
data class TunnelInfo(
    val address: String? = null,
    val dns: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val mtu: Int? = null,
    val remote: String? = null,
    /** The address the internet sees through the tunnel, once it is looked up. */
    val publicIp: String? = null,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
)

enum class LogKind { Plain, Ok, Err }

data class LogLine(val time: String, val text: String, val kind: LogKind)

/** A picked file, read and checked but not saved yet. */
data class ImportReview(
    val fileName: String,
    val text: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: Protocol,
    val needsLogin: Boolean,
    val username: String,
    val password: String,
    val warnings: List<String>,
    /** Why the file cannot be imported, if it cannot. */
    val problem: String? = null,
)

data class AppSettings(
    val killSwitch: Boolean = true,
    /** Connect the startup profile when the TV turns on. */
    val boot: Boolean = false,
    val startupId: String? = null,
    /** Keep casting and devices at home reachable outside the tunnel. */
    val allowLan: Boolean = false,
    /** Send IPv6 into the tunnel, where it is dropped, so it cannot leak. */
    val blockIpv6: Boolean = true,
    /** Show the advanced settings categories. */
    val advanced: Boolean = false,
    /** "Off", "For settings" or "For everything". The PIN itself is kept hashed in [SettingsStore]. */
    val pinMode: String = "Off",
    /** Reconnect by itself after the network changes. */
    val roam: Boolean = true,
    /** Keep the TV awake while the tunnel is up, so it survives the screensaver and sleep. */
    val stayAwake: Boolean = false,
    /** With the local network allowed, still send DNS lookups into the tunnel. */
    val blockDns: Boolean = true,
    /** "Selected apps skip the VPN" or "Only selected apps use it". */
    val appMode: String = APPS_SKIP,
    /** Package names of the selected apps. */
    val apps: Set<String> = emptySet(),
    /** OpenVPN: "From file", "UDP" or "TCP". */
    val proto: String = "From file",
    /** OpenVPN: seconds before giving up on a server. */
    val timeout: Int = 60,
    /** OpenVPN: "Auto" or a number of bytes. */
    val mtu: String = "Auto",
    /** OpenVPN: "TLS 1.2" or "TLS 1.3". */
    val tlsMin: String = "TLS 1.2",
    val legacyCiphers: Boolean = false,
    /** Compress what the TV sends too. Pushed compression is always accepted. */
    val compress: Boolean = false,
    /** "1 · quiet", "3 · normal" or "4 · detailed". */
    val verb: String = "3 · normal",
    /** DNS: use the servers the VPN pushes. */
    val pushedDns: Boolean = true,
    val customDns: List<String> = emptyList(),
    val searchDomains: List<String> = emptyList(),
    /** Let other apps connect and disconnect Tunnel with intents. */
    val automation: Boolean = false,
    /** Look for a newer build on GitHub once a day. */
    val autoUpdate: Boolean = true,
) {
    companion object {
        const val APPS_SKIP = "Selected apps skip the VPN"
        const val APPS_ONLY = "Only selected apps use it"
    }
}

/** Settings › About › Updates: where looking for, fetching and installing a newer build stands. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** [found]: the daily check found it, so the screens mention it once. */
    data class Available(val build: Updates.Published, val found: Boolean = false) : UpdateState
    data class Downloading(val build: Updates.Published, val percent: Int) : UpdateState
    data class Installing(val build: Updates.Published) : UpdateState
    data class Failed(val message: String) : UpdateState
}

data class VpnUiState(
    val connections: List<Connection> = emptyList(),
    val servers: Map<String, Server> = emptyMap(),
    val logins: Map<String, Login> = emptyMap(),
    val infos: Map<String, ConfigInfo> = emptyMap(),
    /** The profile Connect shows and the remote's play key uses. */
    val activeId: String? = null,
    val status: VpnStatus = VpnStatus.Disconnected,
    val settings: AppSettings = AppSettings(),
    val log: List<LogLine> = emptyList(),
    val tunnel: TunnelInfo? = null,
    /** Set while the tunnel is paused, until it comes back by itself. */
    val pausedUntil: Long? = null,
    val review: ImportReview? = null,
    /** A backup file that arrived and waits for its passphrase. */
    val pendingRestore: ByteArray? = null,
    /** A .p12 certificate that arrived and waits for its password. */
    val pendingP12: ByteArray? = null,
    val hasBackupPassphrase: Boolean = false,
    val hasPin: Boolean = false,
    /** The last speed test of each profile, and [SpeedTest.DIRECT] for one without the VPN. */
    val speeds: Map<String, SpeedTest.Result> = emptyMap(),
    val update: UpdateState = UpdateState.Idle,
) {
    val active: Connection? get() = connections.firstOrNull { it.id == activeId }

    fun connection(id: String?): Connection? = connections.firstOrNull { it.id == id }

    fun serverOf(c: Connection): Server? = servers[c.serverId]

    fun infoOf(c: Connection): ConfigInfo? = infos[c.serverId]

    /** True when connecting would have to ask for a username or password first. */
    fun asksForLogin(c: Connection): Boolean {
        val info = infoOf(c) ?: return false
        val login = logins[c.id]
        // Without the server's certificate it fails anyway; say that first, not ask for a password.
        return info.needsLogin && info.hasCa && (login == null || login.username.isEmpty() || !login.hasPassword)
    }
}
