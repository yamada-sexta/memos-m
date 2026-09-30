package org.example.memosm.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ConnectivityObserver"

/**
 * Observes the device network state and exposes whether the app is online
 * and whether the active network is Wi-Fi (used to gate pre-downloads).
 *
 * This is the source of truth for offline detection - unlike fetch exceptions,
 * it lets us skip network requests entirely (and their long timeouts) when
 * the device has no connectivity.
 *
 * Online/offline is derived from the set of networks reported by
 * [ConnectivityManager.NetworkCallback] (not from [ConnectivityManager.activeNetwork],
 * which lags behind inside the onLost callback and would leave us "online"
 * after every network is gone).
 */
class ConnectivityObserver(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(checkOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isWifi = MutableStateFlow(checkWifi())
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()

    private val stateLock = Any()

    /** Networks currently reported as available by the system. */
    private val networks = mutableSetOf<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "onAvailable: network=$network")
            synchronized(stateLock) { networks.add(network) }
            updateState()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "onLost: network=$network")
            synchronized(stateLock) { networks.remove(network) }
            updateState()
        }

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) {
            // Pre-existing networks (already connected when the callback was
            // registered) never get onAvailable, but their capabilities still
            // fire here - treat the active network as "ours" too.
            val tracked = synchronized(stateLock) { network in networks }
            if (tracked || network == connectivityManager.activeNetwork) {
                updateState()
            }
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            updateState()
            // Cold-start race: at app launch the activeNetwork may still be
            // null (or the callback burst not delivered yet), which would make
            // the app treat a connected device as offline and serve stale
            // cache. Re-check shortly after registration.
            Handler(Looper.getMainLooper()).postDelayed({ updateState() }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Online/offline = any callback-reported network OR the active network
     * with the INTERNET capability. The activeNetwork union covers the
     * cold-start window where the callback set is still empty.
     */
    private fun updateState() {
        val active = connectivityManager.activeNetwork
        val tracked = synchronized(stateLock) { networks.toSet() }
        val nets = tracked + (active?.let { setOf(it) } ?: emptySet())
        _isOnline.value = nets.any { net ->
            connectivityManager.getNetworkCapabilities(net)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
        _isWifi.value = nets.any { net ->
            connectivityManager.getNetworkCapabilities(net)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun checkOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun checkWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
