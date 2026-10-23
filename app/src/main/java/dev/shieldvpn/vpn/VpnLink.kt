package dev.shieldvpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The tunnel runs in its own process (":vpn"), so Android can let the app's screens go while
 * you watch TV and keep only the small VPN process. This is the line between the two. The
 * service hands out a [Server]; the app's [Client] binds to it, gets the state so far, then each
 * change as it happens. Starting, stopping and pausing still go to the service as intents.
 */
object VpnLink {
    const val ACTION = "dev.shieldvpn.action.LINK"

    // The app calls the service.
    private const val REGISTER = IBinder.FIRST_CALL_TRANSACTION
    private const val UNREGISTER = REGISTER + 1
    private const val STATS = REGISTER + 2
    private const val CLEAR_LOG = REGISTER + 3

    // The service calls the app, one way, in order.
    private const val STATUS = REGISTER + 10
    private const val LINES = REGISTER + 11
    private const val TUNNEL = REGISTER + 12
    private const val PAUSED = REGISTER + 13

    private const val TAG = "VpnLink"

    /** In the VPN's process: passes every change in [VpnController] to the app's screens. */
    class Server : Binder(), VpnController.Watcher {
        private val clients = CopyOnWriteArrayList<IBinder>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                REGISTER -> {
                    val client = data.readStrongBinder() ?: return true
                    // The snapshot and the changes after it come in order, with none lost or repeated.
                    VpnController.snapshot { status, log, tunnel, pausedUntil ->
                        clients.add(client)
                        // The pause first: a paused tunnel's status alone reads as disconnected.
                        send(client, PAUSED) { it.writeLong(pausedUntil ?: -1) }
                        send(client, STATUS) { writeStatus(it, status) }
                        send(client, LINES) { writeLines(it, log, replace = true) }
                        send(client, TUNNEL) { writeTunnel(it, tunnel) }
                    }
                    runCatching { client.linkToDeath({ clients.remove(client) }, 0) }.onFailure { clients.remove(client) }
                }
                UNREGISTER -> data.readStrongBinder()?.let { clients.remove(it) }
                STATS -> {
                    val bytes = VpnController.stats?.invoke()
                    reply?.writeInt(if (bytes == null) 0 else 1)
                    if (bytes != null) reply?.writeLongArray(bytes)
                }
                CLEAR_LOG -> VpnController.clearLog()
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }

        override fun status(status: VpnStatus) = broadcast(STATUS) { writeStatus(it, status) }

        override fun line(line: LogLine) = broadcast(LINES) { writeLines(it, listOf(line), replace = false) }

        override fun cleared() = broadcast(LINES) { writeLines(it, emptyList(), replace = true) }

        override fun tunnel(info: TunnelInfo?) = broadcast(TUNNEL) { writeTunnel(it, info) }

        override fun paused(until: Long?) = broadcast(PAUSED) { it.writeLong(until ?: -1) }

        private fun broadcast(code: Int, write: (Parcel) -> Unit) {
            clients.forEach { send(it, code, write) }
        }

        private fun send(client: IBinder, code: Int, write: (Parcel) -> Unit) {
            val p = Parcel.obtain()
            try {
                write(p)
                client.transact(code, p, null, FLAG_ONEWAY)
            } catch (e: RemoteException) {
                clients.remove(client)
            } finally {
                p.recycle()
            }
        }
    }

    /** In the app's process: keeps [VpnController] in step with the service while [open]. */
    class Client(private val context: Context) {
        private var service: IBinder? = null
        private var bound = false

        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                when (code) {
                    STATUS -> VpnController.update(readStatus(data))
                    LINES -> {
                        val replace = data.readInt() == 1
                        VpnController.mirrorLog(readLines(data), replace)
                    }
                    TUNNEL -> VpnController.setTunnel(readTunnel(data))
                    PAUSED -> VpnController.setPaused(data.readLong().takeIf { it >= 0 })
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            }
        }

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                service = binder
                call(REGISTER) { it.writeStrongBinder(callback) }
                VpnController.stats = ::stats
            }

            // The VPN's process ended. Android takes its tunnel down with it.
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                VpnController.stats = null
                VpnController.setTunnel(null)
                VpnController.setPaused(null)
                val status = VpnController.status.value
                if (status.isActive) VpnController.update(VpnStatus.Failed(status.connectionId, "The VPN stopped unexpectedly"))
            }
        }

        /**
         * Binds to the service, creating it if needed, so the screens always know the state.
         * While the screens are closed nothing is bound, and an idle VPN process can go.
         */
        fun open() {
            if (bound) return
            val intent = Intent(context, ShieldVpnService::class.java).setAction(ACTION)
            bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        }

        fun close() {
            if (!bound) return
            call(UNREGISTER) { it.writeStrongBinder(callback) }
            runCatching { context.unbindService(connection) }
            bound = false
            service = null
            VpnController.stats = null
        }

        /** Asks the service to clear its log; the cleared log comes back over the link. */
        fun clearLog() {
            if (!call(CLEAR_LOG) {}) VpnController.clearLog()
        }

        private fun stats(): LongArray? {
            val s = service ?: return null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                s.transact(STATS, data, reply, 0)
                if (reply.readInt() == 1) reply.createLongArray() else null
            } catch (e: RemoteException) {
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        private fun call(code: Int, write: (Parcel) -> Unit): Boolean {
            val s = service ?: return false
            val data = Parcel.obtain()
            return try {
                write(data)
                s.transact(code, data, null, IBinder.FLAG_ONEWAY)
            } catch (e: RemoteException) {
                Log.w(TAG, "The VPN process did not answer: $e")
                false
            } finally {
                data.recycle()
            }
        }
    }

    // ---- What goes over the link -----------------------------------------------------

    private fun writeStatus(p: Parcel, s: VpnStatus) {
        when (s) {
            VpnStatus.Disconnected -> p.writeInt(0)
            is VpnStatus.Connecting -> {
                p.writeInt(1)
                p.writeString(s.connectionId)
            }
            is VpnStatus.Connected -> {
                p.writeInt(2)
                p.writeString(s.connectionId)
                p.writeLong(s.sinceMillis)
            }
            is VpnStatus.Blocked -> {
                p.writeInt(3)
                p.writeString(s.connectionId)
            }
            is VpnStatus.Failed -> {
                p.writeInt(4)
                p.writeString(s.connectionId)
                p.writeString(s.message)
            }
        }
    }

    private fun readStatus(p: Parcel): VpnStatus = when (p.readInt()) {
        1 -> VpnStatus.Connecting(p.readString().orEmpty())
        2 -> VpnStatus.Connected(p.readString().orEmpty(), p.readLong())
        3 -> VpnStatus.Blocked(p.readString().orEmpty())
        4 -> VpnStatus.Failed(p.readString(), p.readString().orEmpty())
        else -> VpnStatus.Disconnected
    }

    private fun writeLines(p: Parcel, lines: List<LogLine>, replace: Boolean) {
        p.writeInt(if (replace) 1 else 0)
        p.writeInt(lines.size)
        lines.forEach {
            p.writeString(it.time)
            p.writeString(it.text)
            p.writeInt(it.kind.ordinal)
        }
    }

    private fun readLines(p: Parcel): List<LogLine> = List(p.readInt()) {
        LogLine(p.readString().orEmpty(), p.readString().orEmpty(), LogKind.entries.getOrElse(p.readInt()) { LogKind.Plain })
    }

    private fun writeTunnel(p: Parcel, t: TunnelInfo?) {
        p.writeInt(if (t == null) 0 else 1)
        if (t == null) return
        p.writeString(t.address)
        p.writeStringList(t.dns)
        p.writeStringList(t.routes)
        p.writeInt(t.mtu ?: -1)
        p.writeString(t.remote)
        p.writeString(t.publicIp)
        p.writeLong(t.bytesIn)
        p.writeLong(t.bytesOut)
    }

    private fun readTunnel(p: Parcel): TunnelInfo? {
        if (p.readInt() == 0) return null
        return TunnelInfo(
            address = p.readString(),
            dns = p.createStringArrayList().orEmpty(),
            routes = p.createStringArrayList().orEmpty(),
            mtu = p.readInt().takeIf { it >= 0 },
            remote = p.readString(),
            publicIp = p.readString(),
            bytesIn = p.readLong(),
            bytesOut = p.readLong(),
        )
    }
}
