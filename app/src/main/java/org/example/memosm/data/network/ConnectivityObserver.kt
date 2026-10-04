package org.example.memosm.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps capability and per-UID restriction events together, including unvalidated LANs. */
internal class NetworkAccessTracker<N> {
    data class Access(val internet: Boolean = false, val validated: Boolean = false,
        val wifi: Boolean = false, val blocked: Boolean = false)
    private val networks = mutableMapOf<N, Access>()
    val online get() = networks.values.any { it.internet && it.validated && !it.blocked }
    val wifi get() = networks.values.any { it.wifi && !it.blocked }
    val blocked get() = networks.isNotEmpty() && networks.values.all { it.blocked }
    fun available(network: N) {
        // A default-network callback stops observing the previous best network.
        networks.keys.retainAll(setOf(network))
        networks.putIfAbsent(network, Access())
    }
    fun lost(network: N) { networks.remove(network) }
    fun capabilities(network: N, internet: Boolean, validated: Boolean, wifi: Boolean) {
        networks[network] = (networks[network] ?: Access()).copy(internet = internet, validated = validated, wifi = wifi)
    }
    fun blocked(network: N, blocked: Boolean) {
        networks[network] = (networks[network] ?: Access()).copy(blocked = blocked)
    }
}

/** Device connectivity is a hint; server reachability remains the authority for sync. */
class ConnectivityObserver(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val stateLock = Any()
    private val tracker = NetworkAccessTracker<Network>()
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    private val _isWifi = MutableStateFlow(false)
    val isWifi: StateFlow<Boolean> = _isWifi.asStateFlow()
    private val _isBlocked = MutableStateFlow(false)
    val isBlocked: StateFlow<Boolean> = _isBlocked.asStateFlow()

    private fun updateState() {
        _isBlocked.value = tracker.blocked
        _isOnline.value = tracker.online
        _isWifi.value = tracker.wifi
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(stateLock) {
            tracker.available(network)
            updateState()
        }
        override fun onLost(network: Network) = synchronized(stateLock) {
            tracker.lost(network)
            updateState()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(stateLock) {
            tracker.capabilities(network,
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            updateState()
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = synchronized(stateLock) {
            Log.d("ConnectivityObserver", "onBlockedStatusChanged: blocked=$blocked")
            tracker.blocked(network, blocked)
            updateState()
        }
    }

    /** Recover a stale restriction hint if Android omitted the unblock callback. */
    fun refreshBlockedState() {
        if (!_isBlocked.value) return
        // activeNetwork is null when the default network is blocked for this UID.
        // Query outside callbacks; an accessible network proves the restriction ended.
        val network = connectivityManager.activeNetwork ?: return
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
        synchronized(stateLock) {
            tracker.available(network)
            tracker.capabilities(network,
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            tracker.blocked(network, false)
            updateState()
        }
    }

    init {
        // Seed once outside callbacks. Callback arguments are ordered; synchronous
        // capability queries inside them can return stale data during policy changes.
        connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                tracker.capabilities(network,
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                updateState()
            }
        }
        try {
            // The default network callback also observes unvalidated/local networks.
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.e("ConnectivityObserver", "Failed to register network callback", e)
        }
    }
}
