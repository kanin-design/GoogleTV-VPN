package dev.shieldvpn

import android.app.Application
import dev.shieldvpn.control.Control
import dev.shieldvpn.remote.Devices
import dev.shieldvpn.remote.RemoteApi

/** Starts [Control] in the app's own process. The VPN's process (":vpn") has no use for it. */
class ShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":vpn")) return
        Devices.init(this)
        Control.init(this)
        RemoteApi.start(this)
    }
}
