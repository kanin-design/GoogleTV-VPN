package dev.shieldvpn.vpn

import android.os.ParcelFileDescriptor
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * A WireGuard tunnel, run by wireguard-go. It counts as connected once the server has answered
 * a handshake, not when the interface is up, so a wrong key or a server that is down shows as
 * the same "Server did not respond" an OpenVPN profile gives, after the same Connection timeout.
 */
class WireGuardTunnel(private val host: Host) : TunnelEngine {

    /** What the service does for the engine: the VPN interface, and the events the screens follow. */
    interface Host {
        /** Opens the VPN interface with [spec] and the app's own rules; null when Android refuses. */
        fun openTun(spec: TunSpec): ParcelFileDescriptor?
        fun protect(fd: Int): Boolean
        fun connected()
        fun reconnecting()
        fun log(text: String, kind: LogKind = LogKind.Plain)

        /** A network other than a VPN is up, so a name that doesn't resolve really doesn't exist. */
        fun hasNetwork(): Boolean
    }

    /** The interface a WireGuard file asks for. [allTraffic] and [allIpv6] are default routes in AllowedIPs. */
    data class TunSpec(
        val addresses: List<Pair<String, Int>>,
        val routes: List<Pair<String, Int>>,
        val allTraffic: Boolean,
        val allIpv6: Boolean,
        val dns: List<String>,
        val searchDomains: List<String>,
        val mtu: Int?,
        val remote: String?,
    )

    private lateinit var tunnel: WireGuardFile.Tunnel
    private var timeoutMs = 60_000L
    private var publicKey = ""

    private val lock = Object()
    @Volatile private var handle = -1
    @Volatile private var stopped = false

    override fun prepare(plan: Tunnels.Plan.Ready): String? {
        if (!WireGuardNative.available) return "WireGuard isn’t part of this build."
        val text = WireGuardFile.withSecrets(plan.config, WireGuardFile.Secrets(plan.wgPrivateKey, plan.wgPresharedKeys))
        tunnel = WireGuardFile.tunnel(text) ?: return "The profile's file has an error. Import its .conf again."
        if (tunnel.peers.none { it.host != null }) return "The file has no Endpoint, so there is no server to connect to."
        timeoutMs = plan.timeoutSeconds * 1000L
        publicKey = runCatching { KeyPair(Key.fromBase64(tunnel.privateKey)).publicKey.toBase64() }.getOrDefault("")
        return null
    }

    override fun run(): String {
        val started = System.currentTimeMillis()
        // The servers by address: wireguard-go takes IP addresses, not names.
        val endpoints = HashMap<WireGuardFile.Peer, String>()
        for (p in tunnel.peers) {
            val name = p.host ?: continue
            val ip = resolve(name, started + timeoutMs) ?: return if (stopped) "" else "Couldn’t find the server $name. Check its name, or try again later."
            endpoints[p] = endpointText(ip, p.port ?: 51820)
        }
        if (stopped) return ""
        val pfd = host.openTun(spec(endpoints.values.firstOrNull())) ?: return "Android could not create the VPN interface"
        val h = WireGuardNative.turnOn("wg0", pfd.detachFd(), settings(endpoints))
        if (h < 0) return "WireGuard couldn’t start with this file"
        synchronized(lock) {
            if (stopped) {
                WireGuardNative.turnOff(h)
                return ""
            }
            handle = h
        }
        host.protect(WireGuardNative.socketV4(h))
        host.protect(WireGuardNative.socketV6(h))
        host.log("The tunnel is up. Waiting for the server’s handshake.")
        return watch(started)
    }

    /**
     * Connected at the first handshake; then, while traffic goes out but nothing comes back and the
     * last handshake is older than WireGuard's own limit (180 s), the tunnel is reconnecting, and
     * after the Connection timeout more it has dropped.
     */
    private fun watch(started: Long): String {
        var connected = false
        var staleSince = 0L
        var last = Sample(0, 0, 0)
        var nudged = 0L
        while (!stopped) {
            val now = System.currentTimeMillis()
            val s = sample() ?: return if (stopped) "" else "WireGuard stopped"
            if (!connected) {
                if (s.handshakeMs > 0) {
                    connected = true
                    host.log("The server answered the handshake.", LogKind.Ok)
                    host.connected()
                } else if (now - started > timeoutMs) {
                    return "The server didn’t answer. Check that it has this device’s public key ${shortKey()}"
                } else if (now - nudged > 5_000) {
                    nudge()
                    nudged = now
                }
            } else {
                val sending = s.tx > last.tx
                val hearing = s.rx > last.rx
                val stale = now - s.handshakeMs > REJECT_AFTER_MS && sending && !hearing
                when {
                    stale && staleSince == 0L -> {
                        staleSince = now
                        host.log("No answer from the server for a while. Reconnecting.", LogKind.Err)
                        host.reconnecting()
                    }
                    staleSince != 0L && now - s.handshakeMs < REJECT_AFTER_MS -> {
                        staleSince = 0L
                        host.log("The server answered again.", LogKind.Ok)
                        host.connected()
                    }
                    staleSince != 0L && now - staleSince > timeoutMs -> return "Connection dropped"
                }
            }
            last = s
            pause(if (connected) 2_000 else 500)
        }
        return ""
    }

    /** Waits [ms], or less when the tunnel is stopped meanwhile. */
    private fun pause(ms: Long) {
        synchronized(lock) { if (!stopped) lock.wait(ms) }
    }

    private data class Sample(val rx: Long, val tx: Long, val handshakeMs: Long)

    /** The device's counters and newest handshake. The raw text holds the private key and never leaves here. */
    private fun sample(): Sample? {
        val raw = synchronized(lock) { handle.takeIf { it >= 0 }?.let(WireGuardNative::config) } ?: return null
        var rx = 0L
        var tx = 0L
        var handshake = 0L
        String(raw).lineSequence().forEach { line ->
            val v = line.substringAfter('=', "")
            when (line.substringBefore('=')) {
                "rx_bytes" -> rx += v.toLongOrNull() ?: 0
                "tx_bytes" -> tx += v.toLongOrNull() ?: 0
                "last_handshake_time_sec" -> handshake = maxOf(handshake, (v.toLongOrNull() ?: 0) * 1000)
            }
        }
        raw.fill(0)
        return Sample(rx, tx, handshake)
    }

    /**
     * WireGuard only shakes hands when it has something to send. A small packet to an address the
     * tunnel carries makes that happen now, instead of when an app first sends something.
     */
    private fun nudge() {
        val target = tunnel.peers.flatMap { it.allowedIps }.firstOrNull { ':' !in it }?.let { net ->
            if (net.startsWith("0.0.0.0/")) "1.1.1.1" else firstHost(net)
        } ?: return
        runCatching {
            DatagramSocket().use { s -> s.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName(target), 9)) }
        }
    }

    private fun firstHost(net: String): String? {
        val parts = net.substringBefore('/').split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3] or 1}"
    }

    private fun shortKey() = if (publicKey.length > 12) publicKey.take(8) + "…" + publicKey.takeLast(4) else publicKey

    /**
     * [name] as an address, IPv4 first, tried once a second until [deadline]. Without a network it
     * keeps trying, as one may come; with one, a name that fails five times in a row doesn't exist.
     */
    private fun resolve(name: String, deadline: Long): String? {
        var misses = 0
        while (!stopped) {
            runCatching {
                val all = InetAddress.getAllByName(name)
                return (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress
            }
            if (host.hasNetwork()) misses++ else misses = 0
            if (misses >= 5 || System.currentTimeMillis() > deadline) return null
            pause(1_000)
        }
        return null
    }

    /** wireguard-go's userspace settings: keys in hex, a line each. They hold the private key. */
    private fun settings(endpoints: Map<WireGuardFile.Peer, String>): ByteArray = buildString {
        appendLine("private_key=${WireGuardFile.hex(tunnel.privateKey)}")
        tunnel.listenPort?.let { appendLine("listen_port=$it") }
        appendLine("replace_peers=true")
        tunnel.peers.forEach { p ->
            appendLine("public_key=${WireGuardFile.hex(p.publicKey)}")
            p.presharedKey?.let { appendLine("preshared_key=${WireGuardFile.hex(it)}") }
            endpoints[p]?.let { appendLine("endpoint=$it") }
            p.keepalive?.let { appendLine("persistent_keepalive_interval=$it") }
            appendLine("replace_allowed_ips=true")
            p.allowedIps.forEach { appendLine("allowed_ip=$it") }
        }
    }.toByteArray()

    private fun spec(remote: String?): TunSpec {
        val allowed = tunnel.peers.flatMap { it.allowedIps }
        fun split(net: String): Pair<String, Int>? {
            val address = net.substringBefore('/')
            val prefix = net.substringAfter('/', "").toIntOrNull() ?: if (':' in address) 128 else 32
            return address to prefix
        }
        val allTraffic = allowed.any { it == "0.0.0.0/0" }
        val allIpv6 = allowed.any { it == "::/0" }
        return TunSpec(
            addresses = tunnel.addresses.mapNotNull(::split),
            routes = allowed.filter { it != "0.0.0.0/0" && it != "::/0" }.mapNotNull(::split),
            allTraffic = allTraffic,
            allIpv6 = allIpv6,
            dns = tunnel.dns,
            searchDomains = tunnel.searchDomains,
            mtu = tunnel.mtu,
            remote = remote?.substringBeforeLast(':')?.trim('[', ']'),
        )
    }

    override fun stop() {
        stopped = true
        synchronized(lock) {
            if (handle >= 0) WireGuardNative.turnOff(handle)
            handle = -1
            lock.notifyAll()
        }
    }

    override fun stats(): LongArray? = sample()?.let { longArrayOf(it.rx, it.tx) }

    // wireguard-go follows a new network by itself: its sockets go out on whichever network is there.
    override fun networkChanged() = Unit

    override fun close() = stop()

    private companion object {
        /** WireGuard's RejectAfterTime: a session is no good after this long without a new handshake. */
        const val REJECT_AFTER_MS = 180_000L
    }

    /** Address and port, as wireguard-go's "endpoint=" wants them. */
    private fun endpointText(ip: String, port: Int) = if (':' in ip) "[$ip]:$port" else "$ip:$port"
}
