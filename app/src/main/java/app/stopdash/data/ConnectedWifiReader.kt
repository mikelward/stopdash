package app.stopdash.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the phone reads of the Wi-Fi it's on: the access point's [bssid], the network's [ssid] (its
 * name, null when Android withholds it), and whether the network is [metered]. The name is only ever
 * kept as a keyed id ([WifiPlacesStore.networkId]); this reading is where it stops.
 */
data class WifiReading(val bssid: String, val ssid: String?, val metered: Boolean)

/**
 * The Wi-Fi access point the phone is on now ([WifiReading]), for recognizing a saved place by it
 * (SPEC *Finding stops → Wi-Fi at saved places*), or null: not on Wi-Fi, no precise location (Android
 * withholds the access point's address without it), or no answer within [TIMEOUT_MILLIS]. It reads
 * what Android already holds about the network it's on: no scan, so no radio to wake and no battery
 * to speak of, and nothing about any other network.
 *
 * Android 12 and later leave the address out of what a network callback is told unless the callback
 * asks for it ([ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO]), and only tell one
 * holding precise location; the system then counts it as a use of location, which it is.
 */
class ConnectedWifiReader(
    private val context: Context,
    private val warn: (String) -> Unit = {},
) {
    suspend fun connected(): WifiReading? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        // Not on Wi-Fi: nothing to wait for, so no callback is asked for at all (it can't say there is
        // none, so every read off Wi-Fi would wait out the timeout). Any Wi-Fi network the phone is on,
        // not only the default one: behind a VPN, or with mobile data the default because the Wi-Fi has
        // no internet, the phone is still on the Wi-Fi (Codex). getAllNetworks is deprecated for
        // following networks over time; a one-off "is there one" is what it still answers.
        @Suppress("DEPRECATION")
        val onWifi = manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (!onWifi) return null
        val answer = CompletableDeferred<WifiReading?>()
        val callback = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Any Wi-Fi network the phone is on, not only the default one (see above).
                connectedWifi(capabilities)?.let(answer::complete)
            }
        }
        try {
            manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
        } catch (e: SecurityException) {
            // Network state is an install-time permission, so this is a revoke racing the check.
            warn("Wi-Fi read failed: permission")
            return null
        } catch (e: RuntimeException) {
            // Too many callbacks registered, most often: the place is just not recognized this time.
            warn("Wi-Fi read failed: ${e::class.simpleName}")
            return null
        }
        return try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { answer.await() }
        } finally {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // Already gone (the network went away with it): nothing left to remove.
                warn("Wi-Fi read: callback already removed")
            }
        }
    }

    private fun connectedWifi(capabilities: NetworkCapabilities): WifiReading? {
        val info = capabilities.transportInfo as? WifiInfo ?: return null
        val bssid = info.bssid ?: return null
        return WifiReading(bssid, networkName(info.ssid), metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    companion object {
        /**
         * The network's name from [ssid] as Android reports it: without the quotes it wraps a readable
         * name in (one it can't read as text comes as hex, unquoted, and is used as it is), or null when
         * withheld or blank.
         */
        fun networkName(ssid: String?): String? =
            ssid?.takeIf { it != WifiManager.UNKNOWN_SSID }?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }

        /** How long the network's details are waited for: Android answers a new callback at once. */
        const val TIMEOUT_MILLIS = 1_000L
    }
}
