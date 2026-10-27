package dev.shieldvpn.control

import dev.shieldvpn.vpn.VpnController
import dev.shieldvpn.vpn.VpnStatus
import dev.shieldvpn.vpn.connectionId
import dev.shieldvpn.vpn.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Apply and test: the stores are copied, the change is applied, the profile is connected (with
 * the kill switch holding traffic back while it reconnects), then the tunnel, DNS and the internet
 * are checked. A failed check puts the copy back and returns to the tunnel there was before.
 * Main thread, like [Control].
 */
object Verify {
    sealed interface Outcome {
        data class Passed(val checks: List<ConnectionTest.Check>, val added: String?) : Outcome
        data class RolledBack(val reason: String, val checks: List<ConnectionTest.Check>) : Outcome
    }

    /**
     * Runs [apply], then tests profile [testId] ("new" for the profile [apply] adds). [step] says
     * what is happening, for the phone.
     */
    suspend fun run(testId: String, apply: suspend () -> Pair<String?, String?>, step: (String) -> Unit): Outcome {
        val before = VpnController.status.value
        val beforeId = before.connectionId?.takeIf { before.isActive || VpnController.pausedUntil.value != null }
        Control.snapshot()
        return try {
            test(testId, beforeId, apply, step)
        } catch (t: Throwable) {
            // Cut off or failed part way: the old settings and tunnel come back all the same.
            withContext(NonCancellable) {
                Control.rollBack()
                back(beforeId)
            }
            throw t
        }
    }

    private suspend fun test(testId: String, beforeId: String?, apply: suspend () -> Pair<String?, String?>, step: (String) -> Unit): Outcome {
        val (added, problem) = apply()
        if (problem != null) {
            Control.rollBack()
            back(beforeId)
            return Outcome.RolledBack("$problem ${undone(testId)}", emptyList())
        }
        val id = if (testId == "new") added else testId
        val c = id?.let(Control::connection) ?: run {
            Control.rollBack()
            back(beforeId)
            return Outcome.RolledBack("There was nothing to test. ${undone(testId)}", emptyList())
        }
        step("Connecting ${c.name}")
        Control.connectProblem(c.id)?.let { why ->
            Control.rollBack()
            back(beforeId)
            return Outcome.RolledBack("${c.name} couldn’t connect: ${why.trimEnd('.')}. ${undone(testId)}", emptyList())
        }
        val started = System.currentTimeMillis()
        if (!Control.connect(c.id)) {
            Control.rollBack()
            back(beforeId)
            return Outcome.RolledBack("${c.name} needs its password saved to be tested. ${undone(testId)}", emptyList())
        }
        val timeout = (Control.state.value.settings.timeout + 20) * 1000L
        // The status there was before the connect doesn't count: a new Connected, or a failure after it.
        var seen = 0
        val end = withTimeoutOrNull(timeout) {
            VpnController.status.first { s ->
                val after = seen++ > 0
                when (s) {
                    is VpnStatus.Connected -> s.connectionId == c.id && s.sinceMillis >= started - 2_000
                    is VpnStatus.Failed, is VpnStatus.Blocked -> after && s.connectionId == c.id
                    VpnStatus.Disconnected -> after
                    is VpnStatus.Connecting -> false
                }
            }
        }
        val connected = end is VpnStatus.Connected
        val checks = mutableListOf(
            if (connected) ConnectionTest.tunnel(true, VpnController.tunnel.value)
            else ConnectionTest.Check(ConnectionTest.TUNNEL, ConnectionTest.Result.Fail, (end as? VpnStatus.Failed)?.message ?: "It didn’t connect in time"),
        )
        if (connected) {
            step("Checking DNS")
            // The interface's details come a moment after Connected.
            withTimeoutOrNull(3_000) { VpnController.tunnel.first { it?.dns?.isNotEmpty() == true } }
            checks += ConnectionTest.dns(VpnController.tunnel.value)
            step("Checking the internet")
            checks += ConnectionTest.internet()
        }
        val failed = checks.firstOrNull { !it.ok }
        if (failed == null) {
            Control.keepSnapshot()
            // A test of a profile that wasn't the one up: back to the tunnel there was.
            if (beforeId != c.id && testId != "new") back(beforeId)
            if (testId == "new" && beforeId != null && beforeId != c.id) back(beforeId)
            if (testId == "new" && beforeId == null) Control.disconnect()
            return Outcome.Passed(checks, added)
        }
        step(if (testId == "new") "Taking the profile away again" else "Putting the old settings back")
        Control.rollBack()
        back(beforeId)
        val what = when (failed.title) {
            ConnectionTest.TUNNEL -> "It didn’t connect: ${failed.detail.trimEnd('.').replaceFirstChar { it.lowercase() }}."
            ConnectionTest.DNS -> "DNS didn’t answer through the tunnel."
            else -> "Traffic didn’t reach the internet through the tunnel."
        }
        return Outcome.RolledBack("$what ${undone(testId)}", checks)
    }

    /** How a rollback ends: a new profile isn't kept; a change is undone. */
    private fun undone(testId: String) = if (testId == "new") "It wasn’t saved." else "The old settings are back."

    /** Back to the tunnel that was up before, or none. */
    private fun back(id: String?) {
        if (id != null && Control.connection(id) != null) Control.connect(id) else Control.disconnect()
    }
}
