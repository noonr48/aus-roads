/*
 * withNetwork-flavor location providers — the real fused-location implementations
 * backing the blue dot and the "My Location" FAB.
 *
 * These functions (and the play-services-location dependency they need) are
 * deliberately confined to the withNetwork source set so the privacy-first
 * `offline` flavor links no Google Play Services and compiles no location code.
 * The `offline` source set provides no-op twins with identical signatures, so the
 * shared MapScreen in src/main compiles unchanged against either flavor.
 */
package au.com.ausroads.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

private const val MY_LOCATION_ZOOM = 15.0

/**
 * Live location stream for the blue dot + FAB. Subscribes to fused location updates
 * while [hasPermission] is true AND the screen is foregrounded (started), pausing on
 * ON_STOP so it does not drain the battery while backgrounded. Unlike `lastLocation`,
 * `requestLocationUpdates` actually delivers a fix on a fresh device/emulator.
 */
@Composable
internal fun rememberUserLocation(hasPermission: Boolean): State<Location?> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val location = remember { mutableStateOf<Location?>(null) }

    DisposableEffect(hasPermission, lifecycleOwner) {
        if (!hasPermission) {
            location.value = null
            return@DisposableEffect onDispose { }
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location.value = it }
            }
        }

        @SuppressLint("MissingPermission")
        fun start() {
            // Seed immediately: lastLocation is fast but often null on a cold device,
            // so also request a single fresh fix so the blue dot appears promptly.
            runCatching {
                client.lastLocation.addOnSuccessListener { loc -> if (loc != null) location.value = loc }
            }
            runCatching {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc -> if (loc != null) location.value = loc }
            }
            runCatching {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            }
        }
        fun stop() = client.removeLocationUpdates(callback)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    return location
}

/**
 * Recenters the camera on the user. Uses the live [cached] fix when available;
 * otherwise actively requests a single fresh fix (getCurrentLocation), and only
 * shows the "unavailable" message when even that fails. Always gives feedback —
 * the old version silently did nothing when lastLocation was null.
 */
@SuppressLint("MissingPermission")
@Suppress("LongParameterList")
internal fun recenterOnUser(
    map: MapLibreMap?,
    context: Context,
    cached: Location?,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    locatingMessage: String,
    unavailableMessage: String,
) {
    if (map == null) return
    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) return

    if (cached != null) {
        map.animateToLatLng(cached.latitude, cached.longitude)
        return
    }
    // No fix yet — request a fresh one with feedback.
    scope.launch { snackbarHostState.showSnackbar(locatingMessage) }
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        .addOnSuccessListener { location ->
            if (location != null) {
                map.animateToLatLng(location.latitude, location.longitude)
            } else {
                scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
            }
        }
        .addOnFailureListener {
            scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
        }
}

private fun MapLibreMap.animateToLatLng(lat: Double, lon: Double) {
    animateCamera(
        CameraUpdateFactory.newLatLngZoom(
            LatLng(lat, lon),
            MY_LOCATION_ZOOM,
        ),
        1000,
    )
}
