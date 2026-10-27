package dev.shieldvpn.vpn

/**
 * One tunnel attempt. [ShieldVpnService] owns everything both kinds of VPN share (the VPN
 * interface's rules, the kill switch, pause, the notification, roaming); an engine moves packets.
 */
interface TunnelEngine {
    /** Reads [plan]; null when the tunnel can start, or why it can't, in words for the user. */
    fun prepare(plan: Tunnels.Plan.Ready): String?

    /** Runs on the worker thread until the tunnel ends: "" for an end that was asked for, else the error. */
    fun run(): String

    /** Ends the tunnel, from any thread; [run] returns soon after. */
    fun stop()

    /** Bytes received and sent, in that order, or null. */
    fun stats(): LongArray?

    /** The network under the tunnel changed: Wi-Fi came back, the cable went in. */
    fun networkChanged()

    /** After [run] has returned. */
    fun close()
}

/** OpenVPN 3, as before: the core calls back through [callbacks] for the interface and its events. */
class OpenVpnTunnel(private val callbacks: NativeCallbacks) : TunnelEngine {
    @Volatile private var handle = 0L

    override fun prepare(plan: Tunnels.Plan.Ready): String? {
        val h = OpenVpnEngine.create(callbacks)
        handle = h
        val evalError = OpenVpnEngine.evaluate(h, plan.config, plan.coreOptions)
        if (evalError.isNotEmpty()) return "The profile's file has an error: $evalError"
        if (OpenVpnEngine.needsCredentials(h)) {
            OpenVpnEngine.provideCredentials(h, plan.username, plan.password).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    override fun run(): String = OpenVpnEngine.connect(handle)

    override fun stop() {
        handle.takeIf { it != 0L }?.let(OpenVpnEngine::stop)
    }

    override fun stats(): LongArray? = handle.takeIf { it != 0L }?.let(OpenVpnEngine::stats)

    override fun networkChanged() {
        handle.takeIf { it != 0L }?.let { OpenVpnEngine.reconnect(it, 1) }
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) OpenVpnEngine.destroy(h)
    }
}
