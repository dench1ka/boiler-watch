package ru.dacha.boiler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/** Existing retry alarms remain a fallback when Android/MIUI delays connectivity callbacks. */
class NetworkRecovery(context: Context, private val recovered: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val validated = mutableSetOf<Network>()
    internal val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val changed = synchronized(validated) {
                if (usable) validated.add(network) else { validated.remove(network); false }
            }
            if (changed) recovered()
        }
        override fun onLost(network: Network) { synchronized(validated) { validated.remove(network) } }
    }
    init {
        if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback)
        else manager.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
    }
    fun stop() {
        manager.unregisterNetworkCallback(callback)
        synchronized(validated) { validated.clear() }
    }
}
