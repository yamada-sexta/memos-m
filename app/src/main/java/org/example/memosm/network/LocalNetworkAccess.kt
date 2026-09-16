package org.example.memosm.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.Inet6Address
import java.net.UnknownHostException

fun hasLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 37 || ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_LOCAL_NETWORK
    ) == PackageManager.PERMISSION_GRANTED

/** Resolve ordinary DNS off the UI thread; .local must be guarded before mDNS resolution. */
suspend fun serverNeedsLocalNetworkPermission(context: Context, url: String): Boolean = runInterruptible(Dispatchers.IO) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork
    val capabilities = connectivity.getNetworkCapabilities(network)
    // Android's local network restriction excludes traffic over cellular and VPN networks.
    if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
        return@runInterruptible false
    }
    val routes = connectivity.getLinkProperties(network)?.routes.orEmpty()
        .filter { !it.isDefaultRoute && it.gateway?.isAnyLocalAddress != false }
    serverUsesLocalNetwork(url, isOnLinkAddress = { address ->
        address is Inet6Address && routes.any { it.matches(address) }
    })
}

internal fun serverUsesLocalNetwork(
    url: String,
    isOnLinkAddress: (InetAddress) -> Boolean = { false },
    resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
): Boolean {
    val host = url.toHttpUrlOrNull()?.host?.lowercase()?.trimEnd('.') ?: return false
    if (host == "localhost" || host.endsWith(".localhost")) return false
    // Single-label names and mDNS names may require local resolution before we can get an IP.
    if (host.endsWith(".local") || (!host.contains('.') && !host.contains(':'))) return true
    return try {
        resolve(host).any { isLocalNetworkAddress(it) || isOnLinkAddress(it) }
    } catch (_: UnknownHostException) {
        // Let the normal request report DNS failures. Don't request access for an unknown host.
        false
    }
}

internal fun isLocalNetworkAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isAnyLocalAddress) return false
    val bytes = address.address
    val isUniqueLocalIpv6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    val isCarrierGradeNat = bytes.size == 4 && bytes[0].toInt() == 100 &&
        (bytes[1].toInt() and 0xc0) == 0x40
    val isBroadcast = bytes.size == 4 && bytes.all { it.toInt() == -1 }
    return address.isSiteLocalAddress || address.isLinkLocalAddress || isUniqueLocalIpv6 ||
        isCarrierGradeNat || address.isMulticastAddress || isBroadcast
}
