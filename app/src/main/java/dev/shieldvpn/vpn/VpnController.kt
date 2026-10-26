package dev.shieldvpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tunnel's state. In the VPN's process (":vpn") the service writes it and [VpnLink] passes every
 * change on; in the app's process it mirrors what the link reports, and the UI reads it.
 */
object VpnController {
    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<LogLine>>(emptyList())

    /** The OpenVPN core's output for this session, newest last. */
    val log: StateFlow<List<LogLine>> = _log.asStateFlow()

    private val _tunnel = MutableStateFlow<TunnelInfo?>(null)

    /** The interface Android set up for the tunnel, while there is one. */
    val tunnel: StateFlow<TunnelInfo?> = _tunnel.asStateFlow()

    private val _pausedUntil = MutableStateFlow<Long?>(null)

    /** While paused: when the service brings the tunnel back by itself, in epoch milliseconds. */
    val pausedUntil: StateFlow<Long?> = _pausedUntil.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    /** Told about each change, in the VPN's process. Changes and calls hold this object's lock. */
    interface Watcher {
        fun status(status: VpnStatus)
        fun line(line: LogLine)
        fun cleared()
        fun tunnel(info: TunnelInfo?)
        fun paused(until: Long?)
    }

    @Volatile var watcher: Watcher? = null

    fun update(status: VpnStatus) {
        synchronized(this) {
            _status.value = status
            watcher?.status(status)
        }
    }

    fun log(text: String, kind: LogKind = LogKind.Plain) {
        synchronized(this) {
            val line = LogLine(clock.format(Date()), text, kind)
            _log.value = (_log.value + line).takeLast(LOG_LINES)
            watcher?.line(line)
        }
    }

    fun clearLog() {
        synchronized(this) {
            _log.value = emptyList()
            watcher?.cleared()
        }
    }

    fun setTunnel(info: TunnelInfo?) {
        synchronized(this) {
            _tunnel.value = info
            watcher?.tunnel(info)
        }
    }

    fun updateTunnel(change: (TunnelInfo) -> TunnelInfo) {
        synchronized(this) {
            val next = _tunnel.value?.let(change) ?: return
            _tunnel.value = next
            watcher?.tunnel(next)
        }
    }

    fun setPaused(until: Long?) {
        synchronized(this) {
            _pausedUntil.value = until
            watcher?.paused(until)
        }
    }

    /** The app's process: log lines from the link, the whole log when [replace] is set. */
    fun mirrorLog(lines: List<LogLine>, replace: Boolean) {
        synchronized(this) {
            _log.value = (if (replace) lines else _log.value + lines).takeLast(LOG_LINES)
        }
    }

    /** Runs [block] with no change in between, so the link can send a consistent snapshot. */
    fun <T> snapshot(block: (VpnStatus, List<LogLine>, TunnelInfo?, Long?) -> T): T = synchronized(this) {
        block(_status.value, _log.value, _tunnel.value, _pausedUntil.value)
    }

    /** Reads the running tunnel's byte counters; set by the service while the core runs, and by the link in the app. */
    @Volatile var stats: (() -> LongArray?)? = null

    private const val LOG_LINES = 300
}
