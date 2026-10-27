package dev.shieldvpn.remote

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.shieldvpn.control.Control
import dev.shieldvpn.http.HttpServer

/**
 * Keeps the panel's server up in the app's process while the VPN is on, without the screens: the
 * VPN service binds it while a tunnel is up or paused, so Android keeps this process as long as
 * that service, with no notification of its own. It has no screens, view model or Compose.
 */
class RemoteService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var watch: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Control.attachLink()
        HttpServer.hold(HOLDER)
        // Started again after the last phone went (removed, or unused for 90 days): nothing needs it.
        if (Devices.list.value.isEmpty()) Control.remoteChanged()
        // The server follows the home network to a new address, without waking up to look.
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = recheck()
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = recheck()
            override fun onLost(network: Network) = recheck()
        }
        watch = runCatching { cm.registerNetworkCallback(request, callback); callback }.getOrNull()
    }

    private fun recheck() {
        main.post { HttpServer.recheck() }
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onDestroy() {
        watch?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        watch = null
        HttpServer.release(HOLDER)
        Control.detachLink()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val HOLDER = "panel while the VPN is on"

        /** The VPN's service keeps this process up for the panel. */
        @Volatile var running = false
            private set
    }
}
