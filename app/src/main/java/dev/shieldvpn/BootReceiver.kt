package dev.shieldvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import dev.shieldvpn.vpn.ConnectionStore
import dev.shieldvpn.vpn.SettingsStore
import dev.shieldvpn.vpn.Tunnels

/** Connects the startup profile when the TV turns on, if Settings › Startup asks for it. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore.load(context)
        val id = settings.startupId ?: return
        if (!settings.boot) return
        // Android lets the app start a VPN on its own only after the user has allowed it once.
        if (VpnService.prepare(context) != null) return
        val plan = Tunnels.plan(context, id) as? Tunnels.Plan.Ready ?: return
        Tunnels.start(context, plan)
    }
}

/**
 * Android's Always-on VPN started the VPN's service, which asks here what to connect: the
 * startup profile, else the one Connect shows. The profiles live in this process.
 */
class AlwaysOnReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = SettingsStore.load(context).startupId?.takeIf { ConnectionStore.list(context).any { c -> c.id == it } }
            ?: SettingsStore.activeConnection(context)
            ?: ConnectionStore.list(context).firstOrNull()?.id
        when (val plan = id?.let { Tunnels.plan(context, it) }) {
            is Tunnels.Plan.Ready -> if (!Tunnels.start(context, plan)) Tunnels.stop(context)
            else -> {
                // Said on the profile, where the screens show it next time.
                val why = when (plan) {
                    is Tunnels.Plan.NeedsLogin -> "Always-on VPN could not connect: save the password first"
                    is Tunnels.Plan.Fails -> plan.message
                    else -> null
                }
                if (id != null && why != null) ConnectionStore.update(context, id) { it.copy(lastError = why) }
                Tunnels.stop(context)
            }
        }
    }
}
