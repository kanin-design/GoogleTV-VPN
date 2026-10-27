package dev.shieldvpn.vpn

import android.content.Context
import android.os.Bundle
import dev.shieldvpn.APP_NAME

/** Turns a profile into a tunnel the service can start. Used by the app and at boot. */
object Tunnels {

    sealed interface Plan {
        data class Ready(
            val connectionId: String,
            val config: String,
            val username: String,
            val password: String,
            val killSwitch: Boolean,
            val allowLan: Boolean,
            val blockIpv6: Boolean,
            val dnsServerOnly: Boolean,
            /** "key=value" lines for the core: see [OpenVpnEngine.evaluate]. */
            val coreOptions: String = "",
            val mtu: Int? = null,
            val pushedDns: Boolean = true,
            val customDns: List<String> = emptyList(),
            val searchDomains: List<String> = emptyList(),
            val blockDns: Boolean = true,
            /** Package names that skip the tunnel, or, with [onlyApps], the only ones that use it. */
            val apps: List<String> = emptyList(),
            val onlyApps: Boolean = false,
            val roam: Boolean = true,
            val stayAwake: Boolean = false,
            /** Which engine runs it. [config] is then the .conf without its keys, which come here. */
            val kind: VpnKind = VpnKind.OpenVpn,
            val wgPrivateKey: String = "",
            val wgPresharedKeys: Map<String, String> = emptyMap(),
            /** Settings › OpenVPN › Connection timeout, which WireGuard's first handshake waits for too. */
            val timeoutSeconds: Int = 60,
            /** The profile's name, for the log's lines. */
            val name: String = "",
        ) : Plan {
            // So a log line or a crash report never carries the file's keys or the password.
            override fun toString() = "Plan.Ready($connectionId)"

            /** All of it goes to the VPN's process in the start intent. */
            fun toBundle(): Bundle = Bundle().apply {
                putString("id", connectionId)
                putString("name", name)
                putString("config", config)
                putString("user", username)
                putString("pass", password)
                putBoolean("killSwitch", killSwitch)
                putBoolean("allowLan", allowLan)
                putBoolean("blockIpv6", blockIpv6)
                putBoolean("dnsServerOnly", dnsServerOnly)
                putString("coreOptions", coreOptions)
                putInt("mtu", mtu ?: 0)
                putBoolean("pushedDns", pushedDns)
                putStringArrayList("customDns", ArrayList(customDns))
                putStringArrayList("searchDomains", ArrayList(searchDomains))
                putBoolean("blockDns", blockDns)
                putStringArrayList("apps", ArrayList(apps))
                putBoolean("onlyApps", onlyApps)
                putBoolean("roam", roam)
                putBoolean("stayAwake", stayAwake)
                putString("kind", kind.name)
                putString("wgKey", wgPrivateKey)
                putBundle("wgPsk", Bundle().apply { wgPresharedKeys.forEach { (k, v) -> putString(k, v) } })
                putInt("timeout", timeoutSeconds)
            }

            companion object {
                fun from(b: Bundle?): Ready? {
                    if (b == null) return null
                    return Ready(
                        connectionId = b.getString("id") ?: return null,
                        name = b.getString("name").orEmpty(),
                        config = b.getString("config").orEmpty(),
                        username = b.getString("user").orEmpty(),
                        password = b.getString("pass").orEmpty(),
                        killSwitch = b.getBoolean("killSwitch", true),
                        allowLan = b.getBoolean("allowLan"),
                        blockIpv6 = b.getBoolean("blockIpv6", true),
                        dnsServerOnly = b.getBoolean("dnsServerOnly"),
                        coreOptions = b.getString("coreOptions").orEmpty(),
                        mtu = b.getInt("mtu").takeIf { it > 0 },
                        pushedDns = b.getBoolean("pushedDns", true),
                        customDns = b.getStringArrayList("customDns").orEmpty(),
                        searchDomains = b.getStringArrayList("searchDomains").orEmpty(),
                        blockDns = b.getBoolean("blockDns", true),
                        apps = b.getStringArrayList("apps").orEmpty(),
                        onlyApps = b.getBoolean("onlyApps"),
                        roam = b.getBoolean("roam", true),
                        stayAwake = b.getBoolean("stayAwake"),
                        kind = runCatching { VpnKind.valueOf(b.getString("kind").orEmpty()) }.getOrDefault(VpnKind.OpenVpn),
                        wgPrivateKey = b.getString("wgKey").orEmpty(),
                        wgPresharedKeys = b.getBundle("wgPsk")?.let { k -> k.keySet().associateWith { k.getString(it).orEmpty() } }.orEmpty(),
                        timeoutSeconds = b.getInt("timeout", 60),
                    )
                }
            }
        }

        /** The profile asks for a username and password, and none are saved. */
        data class NeedsLogin(val connectionId: String) : Plan

        data class Fails(val connectionId: String, val message: String) : Plan
    }

    /** [login] connects with a login that is not saved, or replaces the saved one. */
    fun plan(context: Context, id: String, login: CredentialStore.Credentials? = null): Plan {
        val connection = ConnectionStore.list(context).firstOrNull { it.id == id }
            ?: return Plan.Fails(id, "This profile no longer exists.")
        if (ServerStore.hidden(context, connection.serverId)) return Plan.Fails(id, "This profile no longer exists.")
        val base = ServerStore.effectiveConfig(context, connection.serverId)
            ?: return Plan.Fails(id, "The profile's file is missing. Import it again.")
        val settings = SettingsStore.load(context)
        if (ServerStore.list(context).firstOrNull { it.id == connection.serverId }?.kind == VpnKind.WireGuard) {
            // The keys are kept apart from the file, encrypted; the tunnel puts them back.
            val keys = CredentialStore.wireGuard(context, id) ?: return Plan.Fails(id, "The profile's key is missing. Import its .conf file again.")
            return shared(connection, settings).copy(
                config = base, kind = VpnKind.WireGuard, wgPrivateKey = keys.privateKey, wgPresharedKeys = keys.presharedKeys,
            )
        }
        if (!ServerStore.hasCertificate(base)) {
            return Plan.Fails(id, "This profile has no server certificate. Import its .ovpn file again.")
        }
        val creds = login ?: CredentialStore.load(context, id)
        if (ServerStore.needsLogin(base) && (creds.username.isEmpty() || creds.password.isEmpty())) {
            return Plan.NeedsLogin(id)
        }
        val ordered = if (connection.order == "Random" && "remote-random" !in base) "remote-random\n$base" else base
        val config = if (connection.keyAlias != null) Certificates.withoutClientCertificate(ordered) else ordered
        return shared(connection, settings).copy(
            config = config,
            username = creds.username,
            password = creds.password,
            coreOptions = coreOptions(settings) + when {
                connection.keyAlias != null -> "epki=${connection.keyAlias}\n"
                // Without one the core expects a key in the keystore, and fails before it dials.
                // Most providers' files sign in with a username and password only.
                !ServerStore.hasClientCertificate(config) -> "nocert=1\n"
                else -> ""
            },
        )
    }

    /** What every kind of tunnel takes from the app's settings and the profile's own choices. */
    private fun shared(connection: Connection, settings: AppSettings): Plan.Ready {
        val killSwitch = when (connection.killMode) {
            "Always on" -> true
            "Off" -> false
            else -> settings.killSwitch
        }
        // Custom DNS servers replace the pushed ones, unless the profile only trusts its server.
        val useCustom = connection.dnsMode != "Server only" && settings.customDns.isNotEmpty()
        return Plan.Ready(
            connectionId = connection.id,
            name = connection.name,
            config = "",
            username = "",
            password = "",
            killSwitch = killSwitch,
            allowLan = settings.allowLan,
            blockIpv6 = settings.blockIpv6,
            dnsServerOnly = connection.dnsMode == "Server only",
            mtu = settings.mtu.toIntOrNull(),
            pushedDns = connection.dnsMode == "Server only" || (settings.pushedDns && !useCustom),
            customDns = if (useCustom) settings.customDns else emptyList(),
            searchDomains = settings.searchDomains,
            blockDns = settings.blockDns,
            apps = settings.apps.toList(),
            onlyApps = settings.appMode == AppSettings.APPS_ONLY,
            roam = settings.roam,
            stayAwake = settings.stayAwake,
            timeoutSeconds = settings.timeout,
        )
    }

    /** Settings › OpenVPN, in the form the core takes them. */
    private fun coreOptions(s: AppSettings): String = buildString {
        when (s.proto) {
            "UDP" -> appendLine("proto=udp")
            "TCP" -> appendLine("proto=tcp")
        }
        appendLine("timeout=${s.timeout}")
        if (s.tlsMin == "TLS 1.3") appendLine("tlsMin=tls_1_3") else appendLine("tlsMin=tls_1_2")
        if (s.legacyCiphers) appendLine("legacy=1")
        appendLine(if (s.compress) "compress=yes" else "compress=asym")
    }

    /** Returns false when Android refused to start the service. */
    fun start(context: Context, plan: Plan.Ready): Boolean =
        // Newer Android versions refuse this from the background unless the service is already running.
        runCatching { context.startForegroundService(ShieldVpnService.connectIntent(context, plan)) }.onFailure {
            VpnController.update(VpnStatus.Failed(plan.connectionId, "Android did not let ${APP_NAME} connect in the background. Open ${APP_NAME} and connect."))
        }.isSuccess

    fun stop(context: Context) {
        // Refused only when the service is not running, and then there is no tunnel to stop.
        runCatching { context.startService(ShieldVpnService.disconnectIntent(context)) }.onFailure {
            VpnController.update(VpnStatus.Disconnected)
        }
    }

    /**
     * Takes the tunnel down until [until] (epoch milliseconds), when the service brings it back
     * by itself. Pausing again while paused moves that time.
     */
    fun pause(context: Context, until: Long) {
        runCatching { context.startService(ShieldVpnService.pauseIntent(context, until)) }.onFailure {
            VpnController.update(VpnStatus.Disconnected)
        }
    }
}
