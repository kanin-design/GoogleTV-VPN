package dev.shieldvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import dev.shieldvpn.APP_NAME
import dev.shieldvpn.AlwaysOnReceiver
import dev.shieldvpn.MainActivity
import dev.shieldvpn.R
import dev.shieldvpn.remote.Devices
import dev.shieldvpn.remote.RemoteService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the tunnel. The OpenVPN 3 core runs on its own thread; this class
 * turns its events into [VpnStatus], builds the Android VPN interface when the
 * core asks for one, and keeps traffic blocked when the tunnel drops and the
 * kill switch is on.
 *
 * Every change to the session runs on the main thread: the core's thread only blocks in
 * connect() and then hands the end of the session back with [finish].
 */
class ShieldVpnService : VpnService() {

    private val bridge = TunBridge()

    // The app's screens run in another process; they follow the tunnel through this.
    private val link = VpnLink.Server()

    @Volatile private var engine: TunnelEngine? = null
    @Volatile private var connectionId: String? = null
    @Volatile private var killSwitchOn = true
    @Volatile private var allowLan = false
    @Volatile private var blockIpv6 = true
    @Volatile private var dnsServerOnly = false
    @Volatile private var userStopped = false

    // Only a tunnel that came up can drop, so only then does the kill switch engage.
    @Volatile private var wasConnected = false

    // The latest error the core reported for this attempt, in words for the user.
    @Volatile private var failure: String? = null

    // A connect that arrived while another tunnel was running. It starts once that one ends.
    @Volatile private var pending: Tunnels.Plan.Ready? = null
    @Volatile private var worker: Thread? = null

    // While this is open, every packet goes into the VPN and is dropped there,
    // so nothing reaches the internet. A new tunnel replaces it once it exists.
    @Volatile private var blackhole: ParcelFileDescriptor? = null

    // Paused: the tunnel is down, but the service stays in the foreground so the
    // app can bring it back later, even from the background.
    @Volatile private var holding = false

    // The settings for this attempt that the core does not handle itself. Kept after the
    // tunnel ends, so a pause can bring the same tunnel back.
    @Volatile private var plan: Tunnels.Plan.Ready? = null

    // Paused: when the tunnel comes back by itself.
    private val handler = Handler(Looper.getMainLooper())
    private val resume = Runnable { resumeAfterPause() }

    // Guards the engine, which the UI thread reads for the byte counters.
    private val engineLock = Any()

    private var wakeLock: PowerManager.WakeLock? = null
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    // The last start command, so stopping never ends the service under a newer one.
    private var lastStart = 0
    private var destroyed = false

    // Keeps the phone panel's server up in the app's process while a tunnel is up or paused.
    private var remoteBound = false
    private val remote = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        alive++
        VpnController.watcher = link
        link.onKillSwitch = { on -> handler.post { killSwitchNow(on) } }
    }

    /**
     * The kill switch was changed while this tunnel is up. It holds from now; turned off while
     * traffic is held back after a drop, the block ends.
     */
    private fun killSwitchNow(on: Boolean) {
        if (killSwitchOn != on) VpnController.log(if (on) "The kill switch is on for this tunnel now." else "The kill switch is off for this tunnel now.")
        killSwitchOn = on
        plan = plan?.copy(killSwitch = on)
        val status = VpnController.status.value
        if (!on && status is VpnStatus.Blocked && worker == null) {
            VpnController.log("The kill switch was turned off, so traffic is no longer held back.")
            VpnController.update(VpnStatus.Failed(status.connectionId, "Connection dropped"))
            leave()
        }
    }

    /** With phone access while the VPN is on, and a phone paired, the panel's server stays up. */
    private fun checkRemote() {
        val wanted = !destroyed && (worker != null || holding || blackhole != null) &&
            SettingsStore.phoneReach(this) == AppSettings.REACH_VPN && Devices.any(this)
        if (wanted && !remoteBound) {
            remoteBound = runCatching { bindService(Intent(this, RemoteService::class.java), remote, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        } else if (!wanted && remoteBound) {
            runCatching { unbindService(remote) }
            remoteBound = false
        }
    }

    // Android binds with VpnService.SERVICE_INTERFACE for the VPN itself; the app binds for the link.
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == VpnLink.ACTION) link else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        when (intent?.action) {
            ACTION_CONNECT -> {
                // Started with startForegroundService(), so this has to come first.
                showForeground("Connecting")
                connect(Tunnels.Plan.Ready.from(intent.getBundleExtra(EXTRA_PLAN)))
            }
            ACTION_DISCONNECT -> disconnect()
            ACTION_PAUSE -> pause(intent.getLongExtra(EXTRA_UNTIL, System.currentTimeMillis() + 15 * 60_000L))
            ACTION_SIMULATE_DROP -> if (isDebuggable()) simulateDrop()
            ACTION_SIMULATE_RECONNECT -> if (isDebuggable()) synchronized(engineLock) { engine?.networkChanged() }
            ACTION_REMOTE -> if (worker != null || holding || blackhole != null) checkRemote() else if (engine == null) stopSelf(startId)
            SERVICE_INTERFACE -> alwaysOn()
        }
        return START_NOT_STICKY
    }

    private fun connect(next: Tunnels.Plan.Ready?) {
        holding = false
        cancelResume()
        val id = next?.connectionId.orEmpty()
        if (worker != null) {
            // Switching profiles: end this tunnel, and finish() starts the new one. With the kill
            // switch, traffic is held back from now: the old tunnel can take a moment to go down.
            pending = next
            userStopped = true
            next?.name?.takeIf { it.isNotEmpty() }?.let { VpnController.log("Switching to $it.") }
            VpnController.update(VpnStatus.Connecting(id))
            if (wasConnected && next?.killSwitch == true) {
                plan = next
                startBlackhole()
            }
            stopEngine()
            return
        }
        // A blackhole stays until the new tunnel replaces it, so nothing leaks in between.
        userStopped = false
        wasConnected = false
        failure = null
        if (next == null) {
            leave()
            return
        }
        killSwitchOn = next.killSwitch
        allowLan = next.allowLan
        blockIpv6 = next.blockIpv6
        dnsServerOnly = next.dnsServerOnly
        connectionId = id
        plan = next
        if (next.name.isNotEmpty()) VpnController.log("Connecting to ${next.name}.")
        VpnController.update(VpnStatus.Connecting(id))

        val e = if (next.kind == VpnKind.WireGuard) WireGuardTunnel(wireGuardHost) else OpenVpnTunnel(bridge)
        synchronized(engineLock) { engine = e }
        e.prepare(next)?.let { problem ->
            VpnController.log(problem, LogKind.Err)
            finish(VpnStatus.Failed(id, problem))
            return
        }

        worker = Thread({
            val result = runCatching { e.run() }.getOrElse { "The tunnel stopped: ${it.message}" }
            // finish() puts the kill switch's block up while the engine's interface is still open,
            // so nothing slips out in between. Then the engine is freed here, on the thread that ran
            // it: OpenVPN's OpenSSL keeps state for each thread (OpenVpnTunnel).
            val finished = CountDownLatch(1)
            handler.post {
                try {
                    finish(outcome(id, result), closeEngine = false)
                } finally {
                    finished.countDown()
                }
            }
            finished.await(10, TimeUnit.SECONDS)
            e.close()
        }, "tunnel").also { it.start() }
    }

    /**
     * Android's Always-on VPN started the service. The profiles live in the screens' process,
     * so ask it to connect the profile, as it does at boot. It sends Disconnect when it can't.
     */
    private fun alwaysOn() {
        if (worker != null || holding || blackhole != null) return
        VpnController.log("Android's Always-on VPN asked to connect.")
        showForeground("Connecting")
        sendBroadcast(Intent(this, AlwaysOnReceiver::class.java))
    }

    /** The user pressed Disconnect or Cancel. */
    private fun disconnect() {
        pending = null
        userStopped = true
        holding = false
        cancelResume()
        // The worker thread finishes the session once connect() returns.
        if (!stopEngine()) {
            stopBlackhole()
            VpnController.update(VpnStatus.Disconnected)
            leave()
        }
    }

    /** The user pressed Pause: the tunnel goes down until [until], then comes back by itself. */
    private fun pause(until: Long) {
        pending = null
        userStopped = true
        holding = true
        handler.removeCallbacks(resume)
        handler.postDelayed(resume, (until - System.currentTimeMillis()).coerceAtLeast(0))
        // Before the status, so the screens never read the pause as a disconnect.
        VpnController.setPaused(until)
        if (!stopEngine()) {
            stopBlackhole()
            VpnController.update(VpnStatus.Disconnected)
            showForeground("Paused")
        }
    }

    private fun resumeAfterPause() {
        val p = plan ?: return disconnect()
        showForeground("Connecting")
        connect(p)
    }

    private fun cancelResume() {
        handler.removeCallbacks(resume)
        if (VpnController.pausedUntil.value != null) VpnController.setPaused(null)
    }

    /** Debug builds only: ends the core as if the network had failed. */
    private fun simulateDrop() {
        stopEngine()
    }

    /** Asks the running core to end. Returns false when no core is running. */
    private fun stopEngine(): Boolean = synchronized(engineLock) {
        val e = engine
        e?.stop()
        e != null
    }

    /** Android turned the VPN off, or another VPN app took over. */
    override fun onRevoke() {
        VpnController.log("Android turned the VPN off, or another VPN app took over.", LogKind.Err)
        disconnect()
    }

    override fun onDestroy() {
        alive--
        val hadTunnel = synchronized(engineLock) { engine != null } || worker != null || blackhole != null
        // Normally everything is already let go; this covers Android ending the service early.
        destroyed = true
        pending = null
        userStopped = true
        holding = false
        handler.removeCallbacks(resume)
        stopEngine()
        stopWatching()
        releaseWake()
        stopBlackhole()
        if (remoteBound) runCatching { unbindService(remote) }
        remoteBound = false
        VpnController.stats = null
        if (VpnController.watcher === link) VpnController.watcher = null
        super.onDestroy()
        // With no tunnel nothing else lives in this process, so it ends instead of staying cached
        // (about 8 MB). Not when a new start came meanwhile: that service is alive here again.
        if (!hadTunnel) handler.postDelayed({ if (alive == 0) android.os.Process.killProcess(android.os.Process.myPid()) }, 500)
    }

    /** What to tell the user once the core has stopped. */
    private fun outcome(id: String, result: String): VpnStatus {
        val reason = failure
        return when {
            userStopped -> VpnStatus.Disconnected
            reason != null -> VpnStatus.Failed(id, reason)
            result.isNotEmpty() -> VpnStatus.Failed(id, result)
            wasConnected -> VpnStatus.Failed(id, "Connection dropped")
            else -> VpnStatus.Failed(id, "Could not connect")
        }
    }

    /** [closeEngine]: false when the worker frees the engine itself, once this has run. */
    private fun finish(status: VpnStatus, closeEngine: Boolean = true) {
        VpnController.stats = null
        stopWatching()
        releaseWake()
        synchronized(engineLock) {
            if (closeEngine) engine?.close()
            engine = null
        }
        worker = null
        // "Last connected" is when it was last up, so a tunnel that ends counts as now.
        if (wasConnected) connectionId?.let { endedId -> ConnectionStore.update(this, endedId) { it.copy(lastUsed = System.currentTimeMillis()) } }
        if (destroyed) return
        val id = connectionId
        connectionId = null
        VpnController.setTunnel(null)

        val next = pending
        if (next != null) {
            pending = null
            // Switching from a tunnel that was up: block until the next one is, if it asks for that.
            if (wasConnected && next.killSwitch) {
                plan = next
                startBlackhole()
            }
            connect(next)
            return
        }

        // The tunnel dropped on its own, or a new try failed while traffic was held back:
        // keep holding it back instead of letting it leak.
        val retried = !wasConnected && blackhole != null
        if (!userStopped && (wasConnected || retried) && killSwitchOn && id != null && startBlackhole()) {
            // A new tunnel that never came up says why, on the screens too, not only in the log.
            val why = (status as? VpnStatus.Failed)?.message?.takeIf { retried }
            if (why != null) {
                VpnController.log(why, LogKind.Err)
                ConnectionStore.update(this, id) { it.copy(lastError = why) }
            }
            VpnController.log(
                if (retried) "The kill switch is still blocking traffic." else "The tunnel dropped. The kill switch is blocking traffic.",
                LogKind.Err,
            )
            VpnController.update(VpnStatus.Blocked(id, why))
            showForeground("Blocking traffic until the tunnel is back")
            return
        }

        if (holding) {
            VpnController.log("Paused")
            VpnController.update(VpnStatus.Disconnected)
            showForeground("Paused")
            return
        }

        when (status) {
            is VpnStatus.Failed -> {
                VpnController.log(status.message, LogKind.Err)
                status.connectionId?.let { failedId -> ConnectionStore.update(this, failedId) { it.copy(lastError = status.message) } }
            }
            VpnStatus.Disconnected -> VpnController.log("Disconnected")
            else -> Unit
        }
        VpnController.update(status)
        leave()
    }

    /** Shows the status notification that keeps this service in the foreground, or updates its text. */
    private fun showForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tunnel)
            .setContentTitle(APP_NAME)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // After the worker has started, so the check sees the tunnel.
        handler.post { checkRemote() }
    }

    /** Nothing left to own: drop the notification and stop, unless a newer command came in. */
    private fun leave() {
        holding = false
        stopBlackhole()
        checkRemote()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStart)
    }

    /**
     * Opens a VPN interface for all IPv4 and IPv6 traffic and never reads it, so packets queue
     * in the kernel and are dropped there. Nothing reaches the internet, and no thread is spent.
     */
    @Synchronized
    private fun startBlackhole(): Boolean {
        if (blackhole != null) return true
        val pfd = runCatching {
            Builder()
                .setSession("${APP_NAME} (blocked)")
                .addAddress("10.255.255.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd74:756e:6e65:6cff::1", 128)
                .addRoute("::", 0)
                .setMtu(1280)
                .also { applyApps(it, blocking = true) }
                .establish()
        }.getOrNull() ?: return false
        blackhole = pfd
        return true
    }

    @Synchronized
    private fun stopBlackhole() {
        blackhole?.close()
        blackhole = null
    }

    /**
     * Settings › Apps: the apps that skip the tunnel, or the only ones that use it. The app itself
     * stays inside the tunnel, so the details it shows are about the tunnel. While [blocking], it
     * stays outside instead: the core has to look up the server's name to connect again.
     */
    private fun applyApps(builder: Builder, blocking: Boolean = false) {
        val p = plan
        val installed = p?.apps.orEmpty().filter { it != packageName && runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
        if (p?.onlyApps == true && installed.isNotEmpty()) {
            (if (blocking) installed else installed + packageName).forEach { runCatching { builder.addAllowedApplication(it) } }
        } else {
            val outside = if (p?.onlyApps == true) emptyList() else installed
            (if (blocking) outside + packageName else outside).forEach { runCatching { builder.addDisallowedApplication(it) } }
        }
    }

    /** Asks a public echo service, through the tunnel, which address the internet sees. */
    private fun lookUpPublicIp() {
        Thread({
            val ip = PUBLIC_IP_SERVICES.firstNotNullOfOrNull { Http.publicIp(it) }
            if (ip != null && wasConnected) VpnController.updateTunnel { it.copy(publicIp = ip) }
        }, "public-ip").start()
    }

    @Suppress("WakelockTimeout")
    private fun holdWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${APP_NAME}:tunnel")
            .apply { acquire() }
    }

    private fun releaseWake() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** Settings › Startup › Reconnect automatically: a new network gets a fresh connection at once. */
    private fun watchNetworks() {
        if (networkWatch != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val watch = object : ConnectivityManager.NetworkCallback() {
            private var lost = false

            override fun onLost(network: Network) {
                lost = true
            }

            // A network is back after one was lost: Wi-Fi reconnected or the cable is in again.
            override fun onAvailable(network: Network) {
                if (!lost || !wasConnected) return
                lost = false
                VpnController.log("The network changed. Reconnecting.")
                synchronized(engineLock) { engine?.networkChanged() }
            }
        }
        // The networks under the tunnel, not the tunnel itself.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, watch) }.onSuccess { networkWatch = watch }
    }

    private fun stopWatching() {
        networkWatch?.let { w -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(w) } }
        networkWatch = null
    }

    private fun isPrivateV4(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)
    }

    private fun isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** The core's error events, in words for the user. */
    private fun reasonFor(name: String, info: String): String = when {
        name == "AUTH_FAILED" -> "Wrong username or password"
        name == "CONNECTION_TIMEOUT" -> "Server did not respond"
        name == "CERT_VERIFY_FAIL" || name.startsWith("TLS_ALERT") -> "The server's certificate was not accepted"
        name == "TLS_VERSION_MIN" -> "The server's TLS version is too old"
        name == "TUN_SETUP_FAILED" || name == "TUN_IFACE_CREATE" -> "Android could not create the VPN interface"
        name == "TRANSPORT_ERROR" -> "Network error"
        name == "COMPRESS_ERROR" -> "The server's compression settings are not supported"
        name == "NEED_CREDS" -> "This profile needs a username and password"
        info.isNotEmpty() -> info
        else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    // ---- What every tunnel shares -----------------------------------------------------

    /** The tunnel is up: the screens, the notification, the counters and the network watch follow. Any thread. */
    private fun onConnected(id: String) {
        wasConnected = true
        failure = null
        // Recorded here too, so a connect at boot or from another app counts as used.
        ConnectionStore.update(this, id) { it.copy(lastError = null, lastUsed = System.currentTimeMillis()) }
        VpnController.log("Connected", LogKind.Ok)
        VpnController.update(VpnStatus.Connected(id, System.currentTimeMillis()))
        showForeground("Protected")
        VpnController.stats = { synchronized(engineLock) { engine?.stats() } }
        lookUpPublicIp()
        if (plan?.stayAwake == true) holdWake()
        if (plan?.roam != false) watchNetworks()
    }

    private fun onReconnecting(id: String) {
        if (wasConnected && !userStopped) VpnController.update(VpnStatus.Connecting(id))
    }

    /**
     * The app's rules for every VPN interface, after the engine has added its own addresses, routes
     * and DNS servers: the local network, IPv6, custom and fallback DNS, the MTU and which apps use
     * it. With [allTraffic], all IPv4 goes into the tunnel. Returns the details the screens show.
     */
    private fun applyRules(b: Builder, start: TunnelInfo, hasIpv6: Boolean, dnsFromServerAlready: Boolean, allTraffic: Boolean): TunnelInfo {
        var info = start
        var dnsFromServer = dnsFromServerAlready
        fun attempt(block: () -> Any?): Boolean = runCatching { block() != null }.getOrElse { Log.w(TAG, "tun setup failed: ${it.message}"); false }
        if (allTraffic && allowLan) {
            // Everything except the home network, so casting and devices at home keep working.
            PUBLIC_ROUTES.forEach { (address, prefix) -> attempt { b.addRoute(address, prefix) } }
            info = info.copy(routes = info.routes + "All traffic except the local network")
        } else if (allTraffic) {
            attempt { b.addRoute("0.0.0.0", 0) }
            info = info.copy(routes = info.routes + "All traffic")
        }
        if (blockIpv6) {
            // IPv6 goes into the tunnel too. If the server does not carry it, it is dropped there.
            if (!hasIpv6) attempt { b.addAddress("fd74:756e:6e65:6c00::1", 64) }
            attempt { b.addRoute("::", 0) }
        } else if (!hasIpv6) {
            // Android blocks a family the VPN has no address for; Block IPv6 off lets it out.
            attempt { b.allowFamily(OsConstants.AF_INET6) }
        }
        // Android counts a VPN as metered unless told otherwise; this follows the network under it.
        if (Build.VERSION.SDK_INT >= 29) attempt { b.setMetered(false) }
        val p = plan
        p?.customDns?.forEach { dns ->
            if (attempt { b.addDnsServer(dns) }) {
                dnsFromServer = true
                info = info.copy(dns = info.dns + "$dns (custom)")
            }
        }
        p?.searchDomains?.forEach { attempt { b.addSearchDomain(it) } }
        // Without DNS from the server, queries would go to the home router through the tunnel, so
        // use a public resolver instead. A tunnel for some networks only leaves DNS to the network.
        if (!dnsFromServer && !dnsServerOnly && allTraffic) {
            attempt { b.addDnsServer("1.1.1.1") }
            info = info.copy(dns = listOf("1.1.1.1 (fallback)"))
        }
        // With the local network allowed, a DNS server at home would be reached outside
        // the tunnel. Route each one into it anyway.
        if (allowLan && p?.blockDns != false) {
            info.dns.map { it.substringBefore(' ') }.filter { isPrivateV4(it) }.forEach { attempt { b.addRoute(it, 32) } }
        }
        if (p?.mtu != null && info.mtu == null) {
            attempt { b.setMtu(p.mtu) }
            info = info.copy(mtu = p.mtu)
        }
        applyApps(b)
        return info
    }

    /** What the service does for a WireGuard tunnel. It runs on the tunnel's thread. */
    private val wireGuardHost = object : WireGuardTunnel.Host {
        override fun openTun(spec: WireGuardTunnel.TunSpec): ParcelFileDescriptor? = runCatching {
            val b = Builder().setSession(APP_NAME)
            var info = TunnelInfo(remote = spec.remote)
            var hasIpv6 = false
            spec.addresses.forEach { (address, prefix) ->
                b.addAddress(address, prefix)
                if (':' in address) hasIpv6 = true else if (info.address == null) info = info.copy(address = "$address/$prefix")
            }
            spec.routes.forEach { (address, prefix) ->
                // IPv6 networks only go in when IPv6 isn't blocked whole anyway.
                if (':' !in address || !blockIpv6) {
                    b.addRoute(address, prefix)
                    info = info.copy(routes = info.routes + "$address/$prefix")
                }
            }
            if (spec.allIpv6 && !blockIpv6) b.addRoute("::", 0)
            var dnsFromServer = false
            // The file's DNS servers count as the server's: Settings › DNS can replace them.
            if (plan?.pushedDns != false) {
                spec.dns.forEach { dns ->
                    b.addDnsServer(dns)
                    dnsFromServer = true
                    info = info.copy(dns = info.dns + dns)
                }
            }
            spec.searchDomains.forEach { runCatching { b.addSearchDomain(it) } }
            val mtu = plan?.mtu ?: spec.mtu ?: 1280
            b.setMtu(mtu)
            info = info.copy(mtu = mtu)
            info = applyRules(b, info, hasIpv6, dnsFromServer, allTraffic = spec.allTraffic)
            if (!spec.allTraffic) info = info.copy(routes = info.routes + "Only these networks")
            val pfd = b.establish()
            if (pfd != null) {
                stopBlackhole()
                VpnController.setTunnel(info)
            }
            pfd
        }.onFailure { VpnController.log("Android did not open the VPN: ${it.message}", LogKind.Err) }.getOrNull()

        override fun protect(fd: Int): Boolean = fd >= 0 && this@ShieldVpnService.protect(fd)

        override fun connected() {
            connectionId?.let(::onConnected)
        }

        override fun reconnecting() {
            connectionId?.let(::onReconnecting)
        }

        override fun log(text: String, kind: LogKind) = VpnController.log(text, kind)

        override fun hasNetwork(): Boolean = runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
            }
        }.getOrDefault(true)
    }

    /** Receives the core's TUN instructions and applies them to a VpnService.Builder. */
    private inner class TunBridge : NativeCallbacks {
        private var builder: Builder? = null
        private var dnsFromServer = false
        private var hasIpv6 = false
        private var info = TunnelInfo()
        // What the server chose for this session. The file's data-ciphers line is only a guess:
        // the core offers every cipher it has, and the server picks.
        private var cipher: String? = null
        private var compressed = false

        // The core's thread calls these; an exception must not end the VPN's process.
        override fun onEvent(name: String, info: String, error: Boolean) {
            runCatching { event(name, info, error) }.onFailure { Log.w(TAG, "event $name failed: ${it.message}") }
        }

        private fun event(name: String, info: String, error: Boolean) {
            Log.i(TAG, "event=$name error=$error")
            val id = connectionId ?: return
            when (name) {
                "CONNECTED" -> onConnected(id)
                "RECONNECTING" -> onReconnecting(id)
                // Each session asks for its options again, and the server may choose differently.
                "GET_CONFIG" -> {
                    cipher = null
                    compressed = false
                }
                "COMPRESSION_ENABLED" -> {
                    compressed = true
                    VpnController.updateTunnel { it.copy(compressed = true) }
                    VpnController.log("The server compresses what it sends. Video is compressed already, so this only costs the server speed: turn compression off in the server’s settings.")
                }
                // finish() reports how the session ended.
                "DISCONNECTED" -> Unit
                else -> if (error) failure = reasonFor(name, info)
            }
        }

        override fun onLog(text: String) {
            Log.d(TAG, text)
            val line = text.trim()
            if (line.isEmpty()) return
            // The options the server pushed name the data cipher it chose.
            if (line.startsWith("OPTIONS:")) PUSHED_CIPHER.find(line)?.let { cipher = it.groupValues[1] }
            val kind = when {
                "AUTH_FAILED" in line || "rror" in line || "TIMEOUT" in line -> LogKind.Err
                "Session is ACTIVE" in line -> LogKind.Ok
                else -> LogKind.Plain
            }
            runCatching { VpnController.log(line, kind) }
        }

        override fun newBuilder(): Boolean {
            builder = Builder().setSession(APP_NAME)
            dnsFromServer = false
            hasIpv6 = false
            info = TunnelInfo()
            return true
        }

        override fun setRemoteAddress(address: String, ipv6: Boolean): Boolean {
            info = info.copy(remote = address)
            return true
        }

        override fun addAddress(address: String, prefix: Int, ipv6: Boolean): Boolean {
            if (ipv6) hasIpv6 = true else info = info.copy(address = "$address/$prefix")
            return attempt { builder?.addAddress(address, prefix) }
        }

        override fun addRoute(address: String, prefix: Int, ipv6: Boolean): Boolean {
            // With the local network allowed, the default route is replaced in establish().
            if (allowLan && !ipv6 && prefix <= 1) return true
            info = info.copy(routes = info.routes + "$address/$prefix")
            return attempt { builder?.addRoute(address, prefix) }
        }

        override fun setMtu(mtu: Int): Boolean {
            val chosen = plan?.mtu ?: mtu
            info = info.copy(mtu = chosen)
            return attempt { builder?.setMtu(chosen) }
        }

        override fun setSessionName(name: String): Boolean = attempt { builder?.setSession(name) }

        override fun addDns(address: String): Boolean {
            // Settings › DNS can replace the servers the VPN pushes.
            if (plan?.pushedDns == false) return true
            dnsFromServer = true
            info = info.copy(dns = info.dns + address)
            return attempt { builder?.addDnsServer(address) }
        }

        override fun establish(): Int = runCatching { establishTunnel() }.getOrElse {
            // Android refuses when another VPN app took over or the VPN was turned off meanwhile.
            VpnController.log("Android did not open the VPN: ${it.message}", LogKind.Err)
            -1
        }

        private fun establishTunnel(): Int {
            val b = builder ?: return -1
            // OpenVPN always carries all IPv4 traffic, whatever the server pushes.
            info = applyRules(b, info, hasIpv6, dnsFromServer, allTraffic = true).copy(cipher = cipher, compressed = compressed)
            val pfd = b.establish() ?: return -1
            // The new interface has taken over from the blackhole, if one was holding traffic back.
            stopBlackhole()
            VpnController.setTunnel(info)
            // The core takes ownership of the descriptor.
            return pfd.detachFd()
        }

        override fun protectSocket(fd: Int, remote: String, ipv6: Boolean): Boolean =
            this@ShieldVpnService.protect(fd)

        override fun teardown(disconnect: Boolean) {
            builder = null
        }

        // These run on the core's thread, which KeyChain needs: it must not be the main thread.
        override fun pkiCertificate(alias: String): String? =
            Certificates.entry(this@ShieldVpnService, alias)?.second?.firstOrNull()?.let { Certificates.pem("CERTIFICATE", it.encoded) }
                ?: null.also { VpnController.log("The certificate for this profile could not be read. Pick it again in Client certificate.", LogKind.Err) }

        override fun pkiChain(alias: String): String? =
            Certificates.entry(this@ShieldVpnService, alias)?.second?.drop(1)?.joinToString("\n") { Certificates.pem("CERTIFICATE", it.encoded) }

        override fun pkiSign(alias: String, data: String, algorithm: String, hashalg: String, saltlen: String): String? = runCatching {
            val (key, chain) = Certificates.entry(this@ShieldVpnService, alias) ?: error("no key")
            val sig = Certificates.sign(key, chain.first().publicKey, android.util.Base64.decode(data, android.util.Base64.DEFAULT), algorithm, hashalg, saltlen)
            android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP)
        }.onFailure { VpnController.log("The key could not sign ($algorithm ${hashalg.ifEmpty { "" }}): ${it.message}", LogKind.Err) }.getOrNull()

        private fun attempt(block: () -> Any?): Boolean =
            runCatching { block() != null }.getOrElse {
                Log.w(TAG, "tun setup failed: ${it.message}")
                false
            }
    }

    companion object {
        /** Services of this class alive in this process, so an idle process ends only when none is. */
        private var alive = 0

        private const val TAG = "ShieldVpnService"

        const val ACTION_CONNECT = "dev.shieldvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "dev.shieldvpn.action.DISCONNECT"
        const val ACTION_PAUSE = "dev.shieldvpn.action.PAUSE"
        const val ACTION_SIMULATE_DROP = "dev.shieldvpn.action.SIMULATE_DROP"

        /** Debug builds only: the core reconnects, as after a timeout or a new network. */
        const val ACTION_SIMULATE_RECONNECT = "dev.shieldvpn.action.SIMULATE_RECONNECT"

        /** Phone access or the paired phones changed: the service binds or lets go of the panel's server. */
        const val ACTION_REMOTE = "dev.shieldvpn.action.REMOTE"

        private const val CHANNEL = "status"

        /** Asked in turn until one answers; each returns the caller's address as plain text. */
        private val PUBLIC_IP_SERVICES = listOf("https://api.ipify.org", "https://icanhazip.com")
        private const val NOTIFICATION_ID = 1

        private const val EXTRA_PLAN = "plan"
        private const val EXTRA_UNTIL = "until"

        /** One option in the core's "OPTIONS:" log of what the server pushed: `3 [cipher] [AES-256-GCM]`. */
        private val PUSHED_CIPHER = Regex("""\[cipher\] \[([A-Za-z0-9-]+)\]""")

        /** IPv4 without 10/8, 172.16/12, 192.168/16 and multicast: the internet, minus the home network. */
        private val PUBLIC_ROUTES = listOf(
            "0.0.0.0" to 5, "8.0.0.0" to 7, "11.0.0.0" to 8, "12.0.0.0" to 6, "16.0.0.0" to 4, "32.0.0.0" to 3,
            "64.0.0.0" to 2, "128.0.0.0" to 3, "160.0.0.0" to 5, "168.0.0.0" to 6, "172.0.0.0" to 12,
            "172.32.0.0" to 11, "172.64.0.0" to 10, "172.128.0.0" to 9, "173.0.0.0" to 8, "174.0.0.0" to 7,
            "176.0.0.0" to 4, "192.0.0.0" to 9, "192.128.0.0" to 11, "192.160.0.0" to 13, "192.169.0.0" to 16,
            "192.170.0.0" to 15, "192.172.0.0" to 14, "192.176.0.0" to 12, "192.192.0.0" to 10, "193.0.0.0" to 8,
            "194.0.0.0" to 7, "196.0.0.0" to 6, "200.0.0.0" to 5, "208.0.0.0" to 4,
        )

        fun connectIntent(context: Context, plan: Tunnels.Plan.Ready): Intent =
            Intent(context, ShieldVpnService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_PLAN, plan.toBundle())

        fun disconnectIntent(context: Context): Intent =
            Intent(context, ShieldVpnService::class.java).setAction(ACTION_DISCONNECT)

        fun pauseIntent(context: Context, until: Long): Intent =
            Intent(context, ShieldVpnService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_UNTIL, until)

        fun simulateDropIntent(context: Context): Intent =
            Intent(context, ShieldVpnService::class.java).setAction(ACTION_SIMULATE_DROP)

        fun simulateReconnectIntent(context: Context): Intent =
            Intent(context, ShieldVpnService::class.java).setAction(ACTION_SIMULATE_RECONNECT)
    }
}
