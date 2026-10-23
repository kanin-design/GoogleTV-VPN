package dev.shieldvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
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
