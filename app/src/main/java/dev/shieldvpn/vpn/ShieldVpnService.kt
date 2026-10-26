package dev.shieldvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    @Volatile private var handle = 0L
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

    // Guards the native handle, which the UI thread reads for the byte counters.
    private val engineLock = Any()

    private var wakeLock: PowerManager.WakeLock? = null
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    // The last start command, so stopping never ends the service under a newer one.
    private var lastStart = 0
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        VpnController.watcher = link
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
            SERVICE_INTERFACE -> alwaysOn()
        }
        return START_NOT_STICKY
    }

    private fun connect(next: Tunnels.Plan.Ready?) {
        holding = false
        cancelResume()
        val id = next?.connectionId.orEmpty()
        if (worker != null) {
            // Switching profiles: end this tunnel, and finish() starts the new one.
            pending = next
            userStopped = true
            VpnController.update(VpnStatus.Connecting(id))
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
        VpnController.update(VpnStatus.Connecting(id))

        val h = OpenVpnEngine.create(bridge)
        handle = h

        val evalError = OpenVpnEngine.evaluate(h, next.config, next.coreOptions)
        if (evalError.isNotEmpty()) {
            VpnController.log(evalError, LogKind.Err)
            finish(VpnStatus.Failed(id, "The profile's file has an error: $evalError"))
            return
        }
        if (OpenVpnEngine.needsCredentials(h)) {
            val credError = OpenVpnEngine.provideCredentials(h, next.username, next.password)
            if (credError.isNotEmpty()) {
                finish(VpnStatus.Failed(id, credError))
                return
            }
        }

        worker = Thread({
            val result = OpenVpnEngine.connect(h)
            handler.post { finish(outcome(id, result)) }
        }, "openvpn-core").also { it.start() }
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
        val h = handle
        if (h != 0L) OpenVpnEngine.stop(h)
        h != 0L
    }

    /** Android turned the VPN off, or another VPN app took over. */
    override fun onRevoke() {
        VpnController.log("Android turned the VPN off, or another VPN app took over.", LogKind.Err)
        disconnect()
    }

    override fun onDestroy() {
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
        VpnController.stats = null
        if (VpnController.watcher === link) VpnController.watcher = null
        super.onDestroy()
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

    private fun finish(status: VpnStatus) {
        VpnController.stats = null
        stopWatching()
        releaseWake()
        synchronized(engineLock) {
            val h = handle
            if (h != 0L) {
                OpenVpnEngine.destroy(h)
                handle = 0L
            }
        }
        worker = null
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
            if (retried && status is VpnStatus.Failed) VpnController.log(status.message, LogKind.Err)
            VpnController.log(
                if (retried) "The kill switch is still blocking traffic." else "The tunnel dropped. The kill switch is blocking traffic.",
                LogKind.Err,
            )
            VpnController.update(VpnStatus.Blocked(id))
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
    }

    /** Nothing left to own: drop the notification and stop, unless a newer command came in. */
    private fun leave() {
        holding = false
        stopBlackhole()
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
                synchronized(engineLock) { handle.takeIf { it != 0L }?.let { OpenVpnEngine.reconnect(it, 1) } }
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

    /** Receives the core's TUN instructions and applies them to a VpnService.Builder. */
    private inner class TunBridge : NativeCallbacks {
        private var builder: Builder? = null
        private var dnsFromServer = false
        private var hasIpv6 = false
        private var info = TunnelInfo()

        // The core's thread calls these; an exception must not end the VPN's process.
        override fun onEvent(name: String, info: String, error: Boolean) {
            runCatching { event(name, info, error) }.onFailure { Log.w(TAG, "event $name failed: ${it.message}") }
        }

        private fun event(name: String, info: String, error: Boolean) {
            Log.i(TAG, "event=$name error=$error")
            val id = connectionId ?: return
            when (name) {
                "CONNECTED" -> {
                    wasConnected = true
                    failure = null
                    // Recorded here too, so a connect at boot or from another app counts as used.
                    ConnectionStore.update(this@ShieldVpnService, id) { it.copy(lastError = null, lastUsed = System.currentTimeMillis()) }
                    VpnController.log("Connected", LogKind.Ok)
                    VpnController.update(VpnStatus.Connected(id, System.currentTimeMillis()))
                    showForeground("Protected")
                    VpnController.stats = {
                        synchronized(engineLock) { handle.takeIf { it != 0L }?.let { OpenVpnEngine.stats(it) } }
                    }
                    lookUpPublicIp()
                    if (plan?.stayAwake == true) holdWake()
                    if (plan?.roam != false) watchNetworks()
                }
                "RECONNECTING" -> if (wasConnected && !userStopped) VpnController.update(VpnStatus.Connecting(id))
                // finish() reports how the session ended.
                "DISCONNECTED" -> Unit
                else -> if (error) failure = reasonFor(name, info)
            }
        }

        override fun onLog(text: String) {
            Log.d(TAG, text)
            val line = text.trim()
            if (line.isEmpty()) return
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
            if (allowLan) {
                // Everything except the home network, so casting and devices at home keep working.
                PUBLIC_ROUTES.forEach { (address, prefix) -> attempt { builder?.addRoute(address, prefix) } }
                info = info.copy(routes = info.routes + "All traffic except the local network")
            } else {
                // Full tunnel: send all IPv4 traffic through the VPN.
                attempt { builder?.addRoute("0.0.0.0", 0) }
                info = info.copy(routes = info.routes + "All traffic")
            }
            if (blockIpv6) {
                // IPv6 goes into the tunnel too. If the server does not carry it, it is dropped there.
                if (!hasIpv6) attempt { builder?.addAddress("fd74:756e:6e65:6c00::1", 64) }
                attempt { builder?.addRoute("::", 0) }
            } else if (!hasIpv6) {
                // Android blocks a family the VPN has no address for; Block IPv6 off lets it out.
                attempt { builder?.allowFamily(OsConstants.AF_INET6) }
            }
            // Android counts a VPN as metered unless told otherwise; this follows the network under it.
            if (Build.VERSION.SDK_INT >= 29) attempt { builder?.setMetered(false) }
            val p = plan
            p?.customDns?.forEach { dns ->
                if (attempt { builder?.addDnsServer(dns) }) {
                    dnsFromServer = true
                    info = info.copy(dns = info.dns + "$dns (custom)")
                }
            }
            p?.searchDomains?.forEach { attempt { builder?.addSearchDomain(it) } }
            // Without DNS from the server, queries would go to the home router
            // through the tunnel, so use a public resolver instead.
            if (!dnsFromServer && !dnsServerOnly) {
                attempt { builder?.addDnsServer("1.1.1.1") }
                info = info.copy(dns = listOf("1.1.1.1 (fallback)"))
            }
            // With the local network allowed, a DNS server at home would be reached outside
            // the tunnel. Route each one into it anyway.
            if (allowLan && p?.blockDns != false) {
                info.dns.map { it.substringBefore(' ') }.filter { isPrivateV4(it) }.forEach { attempt { builder?.addRoute(it, 32) } }
            }
            if (p?.mtu != null && info.mtu == null) {
                attempt { builder?.setMtu(p.mtu) }
                info = info.copy(mtu = p.mtu)
            }
            builder?.let { applyApps(it) }

            val pfd = builder?.establish() ?: return -1
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
        private const val TAG = "ShieldVpnService"

        const val ACTION_CONNECT = "dev.shieldvpn.action.CONNECT"
        const val ACTION_DISCONNECT = "dev.shieldvpn.action.DISCONNECT"
        const val ACTION_PAUSE = "dev.shieldvpn.action.PAUSE"
        const val ACTION_SIMULATE_DROP = "dev.shieldvpn.action.SIMULATE_DROP"

        private const val CHANNEL = "status"

        /** Asked in turn until one answers; each returns the caller's address as plain text. */
        private val PUBLIC_IP_SERVICES = listOf("https://api.ipify.org", "https://icanhazip.com")
        private const val NOTIFICATION_ID = 1

        private const val EXTRA_PLAN = "plan"
        private const val EXTRA_UNTIL = "until"

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
    }
}
