package dev.grabline.app.engine.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import dev.grabline.app.engine.DownloadEngine

/** Feeds the default network into the engine: Wi-Fi drop -> "Waiting for network", return -> resume. */
class NetworkMonitor(context: Context, private val engine: DownloadEngine) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    fun start() {
        val manager = cm ?: return
        push(manager.activeNetwork?.let { manager.getNetworkCapabilities(it) })
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = push(caps)
                override fun onLost(network: Network) = push(null)
            })
        } catch (_: SecurityException) {
            // No network-state access: leave the engine at "connected".
        }
    }

    private fun push(caps: NetworkCapabilities?) {
        val s = if (caps == null) NetworkState.Offline else NetworkState.fromCapabilities(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            wifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
        engine.setNetworkState(wifi = s.wifi, metered = s.metered, connected = s.connected)
    }
}

/** Feeds "plugged in" into the engine for the "Only while charging" setting. */
class ChargingMonitor(context: Context, private val engine: DownloadEngine) {
    private val app = context.applicationContext

    fun start() {
        val battery = app.getSystemService(BatteryManager::class.java)
        engine.setCharging(battery?.isCharging == true)
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    engine.setCharging(intent.action == Intent.ACTION_POWER_CONNECTED)
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
    }
}
