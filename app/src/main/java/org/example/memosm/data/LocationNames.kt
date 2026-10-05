package org.example.memosm.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.collection.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.memosm.R
import org.example.memosm.model.Location
import org.example.memosm.model.hasValidCoordinates
import java.util.Locale
import kotlin.coroutines.resume

internal data class LocationNameKey(val latitude: Double, val longitude: Double, val locale: String)
private val locationNameCache = LruCache<LocationNameKey, String>(128)

/** Cache successful lookups only; unavailable geocoding can be retried later. */
internal suspend fun cachedLocationName(
    key: LocationNameKey,
    cache: LruCache<LocationNameKey, String> = locationNameCache,
    lookup: suspend () -> String?
): String? = cache[key] ?: lookup()?.takeIf { it.isNotBlank() }?.also { cache.put(key, it) }

internal fun Location.customLocationName(defaultName: String): String? = placeholder?.trim()?.takeIf {
    it.isNotBlank() && it != defaultName && it != "$latitude, $longitude"
}

/** Shared naming for GPS locations and places selected on the map. Keep custom names. */
suspend fun resolveLocationName(context: Context, location: Location): Location = withContext(Dispatchers.IO) {
    if (!location.hasValidCoordinates()) return@withContext location
    val defaultName = context.getString(R.string.memo_composer_location_default_placeholder)
    location.customLocationName(defaultName)?.let { return@withContext location.copy(placeholder = it) }
    val locale = Locale.getDefault()
    val key = LocationNameKey(location.latitude!!, location.longitude!!, locale.toLanguageTag())
    val name = cachedLocationName(key) { reverseGeocode(context, key, locale) }
    location.copy(placeholder = name ?: defaultName)
}

private suspend fun reverseGeocode(context: Context, key: LocationNameKey, locale: Locale): String? =
    try {
        withTimeoutOrNull(5_000) {
            if (!Geocoder.isPresent()) return@withTimeoutOrNull null
            val geocoder = Geocoder(context, locale)
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>> { continuation ->
                    geocoder.getFromLocation(key.latitude, key.longitude, 1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses)
                            }
                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(emptyList())
                            }
                        })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(key.latitude, key.longitude, 1).orEmpty()
            }
            addresses.firstOrNull()?.let { address ->
                listOfNotNull(address.locality, address.subAdminArea, address.adminArea)
                    .filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e("LocationHelper", "Geocoding failed", error)
        null
    }
