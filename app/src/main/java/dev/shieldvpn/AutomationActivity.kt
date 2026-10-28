package dev.shieldvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import dev.shieldvpn.control.Control
import dev.shieldvpn.vpn.ConnectionStore
import dev.shieldvpn.vpn.ServerStore
import dev.shieldvpn.vpn.SettingsStore
import dev.shieldvpn.vpn.Tunnels

/**
 * Settings › Automation: other apps and ADB connect or disconnect the app by starting
 * dev.shieldvpn/.Connect (with an optional "profile" name) or dev.shieldvpn/.Disconnect.
 * It shows nothing and finishes at once. It does nothing until the user turns it on.
 */
class AutomationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        run()
        finish()
    }

    private fun run() {
        if (!SettingsStore.load(this).automation) {
            say("Turn on Settings › Automation in ${APP_NAME} first")
            return
        }
        val connect = intent.component?.className?.endsWith(".Connect") == true
        if (!connect) {
            Tunnels.stop(this)
            return
        }
        val name = intent.getStringExtra(EXTRA_PROFILE)?.trim()
        val connections = ConnectionStore.list(this).filterNot { ServerStore.hidden(this, it.serverId) }
        val target = when {
            !name.isNullOrEmpty() -> connections.firstOrNull { it.name.equals(name, ignoreCase = true) }
            else -> connections.firstOrNull { it.id == SettingsStore.activeConnection(this) } ?: connections.firstOrNull()
        }
        if (target == null) {
            say(if (name.isNullOrEmpty()) "${APP_NAME} has no profiles" else "${APP_NAME} has no profile called $name")
            return
        }
        // Android asks the user once before any app may run a VPN; that needs the app itself.
        if (VpnService.prepare(this) != null) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            say("Open ${APP_NAME} and connect once, so Android allows the VPN")
            return
        }
        when (val plan = Tunnels.plan(this, target.id)) {
            is Tunnels.Plan.Ready -> {
                Control.setActive(target.id)
                Tunnels.start(this, plan)
            }
            is Tunnels.Plan.NeedsLogin -> say("${target.name} needs its password saved first")
            is Tunnels.Plan.Fails -> say(plan.message)
        }
    }

    private fun say(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
