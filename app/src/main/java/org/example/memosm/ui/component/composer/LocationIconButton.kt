package org.example.memosm.ui.component.composer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.example.memosm.R
import org.example.memosm.data.resolveLocationName
import org.example.memosm.model.Location
import kotlin.coroutines.resume

@Composable
fun LocationIconButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
    location: Location? = null,
    onLocationFounded: (Location) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isFetching by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            isFetching = true
            scope.launch {
                val loc = fetchCurrentLocation(context)
                if (loc != null) {
                    onLocationFounded(loc)
                }
                isFetching = false
            }
        }
    }

    IconButton(
        onClick = {
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasFine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCoarse || hasFine) {
                isFetching = true
                scope.launch {
                    val loc = fetchCurrentLocation(context)
                    if (loc != null) {
                        onLocationFounded(loc)
                    }
                    isFetching = false
                }
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        },
        enabled = enabled && !isFetching,
        modifier = modifier
    ) {
        if (isFetching) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = if (location != null) Icons.Default.Place else Icons.Outlined.Place,
                contentDescription = stringResource(R.string.memo_composer_add_location),
                tint = if (location != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Fetches the current location using FusedLocationProviderClient with a fallback to LocationManager.
 * Also performs reverse geocoding to get a placeholder name.
 */
@SuppressLint("MissingPermission")
suspend fun fetchCurrentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
    try {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val cancellationTokenSource = CancellationTokenSource()

        // Try getting location from FusedLocationProvider
        val fusedLocation = suspendCancellableCoroutine<android.location.Location?> { cont ->
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { loc ->
                cont.resume(loc)
            }.addOnFailureListener {
                cont.resume(null)
            }
        }

        if (fusedLocation != null) {
            return@withContext resolveLocationName(context, Location(latitude = fusedLocation.latitude, longitude = fusedLocation.longitude))
        }

        // Fallback to LocationManager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }

        if (provider != null) {
            val androidLoc = suspendCancellableCoroutine<android.location.Location?> { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.getCurrentLocation(
                        provider,
                        null,
                        ContextCompat.getMainExecutor(context)
                    ) { loc -> cont.resume(loc) }
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(
                        provider,
                        object : LocationListener {
                            override fun onLocationChanged(l: android.location.Location) {
                                cont.resume(l)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {
                            }

                            override fun onProviderEnabled(p: String) {}
                            override fun onProviderDisabled(p: String) {}
                        },
                        context.mainLooper
                    )
                }
            }

            if (androidLoc != null) {
                return@withContext resolveLocationName(context, Location(latitude = androidLoc.latitude, longitude = androidLoc.longitude))
            }
        }

    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("LocationHelper", "Location fetch failed", e)
    }

    return@withContext null
}
