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
import android.util.Log
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
private const val TAG = "UserLocation"

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
        // Diagnostics never serialize a Location or the SDK's Throwable payload.
        Log.i(TAG, if (hasPermission) "component=user_location seam=permission class=granted"
            else "component=user_location seam=permission class=missing")
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
                result.lastLocation?.let {
                    Log.d(TAG, "component=user_location seam=stream_fix class=received")
                    location.value = it
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun start() {
            // Explicit guard: lint's MissingPermission flow analysis (and honest
            // runtime behaviour) both require a live permission check here —
            // @SuppressLint on a local function inside a lambda is not reliably
            // honoured, and the permission can be revoked while backgrounded.
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "component=user_location seam=stream_permission class=missing")
                return
            }
            Log.i(TAG, "component=user_location seam=stream_request class=started")
            // Seed immediately: lastLocation is fast but often null on a cold device,
            // so also request a single fresh fix so the blue dot appears promptly.
            runCatching {
                client.lastLocation
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            Log.d(TAG, "component=user_location seam=last_fix class=received")
                            location.value = loc
                        } else {
                            Log.i(TAG, "component=user_location seam=last_fix class=empty")
                        }
                    }
                    .addOnFailureListener { Log.w(TAG, "component=user_location seam=last_task class=failed") }
            }.onFailure { Log.w(TAG, "component=user_location seam=last_call class=failed") }
            runCatching {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            Log.d(TAG, "component=user_location seam=current_fix class=received")
                            location.value = loc
                        } else {
                            Log.i(TAG, "component=user_location seam=current_fix class=empty")
                        }
                    }
                    .addOnFailureListener { Log.w(TAG, "component=user_location seam=current_task class=failed") }
            }.onFailure { Log.w(TAG, "component=user_location seam=current_call class=failed") }
            runCatching {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            }.onFailure { Log.w(TAG, "component=user_location seam=stream_call class=failed") }
        }
        fun stop() {
            Log.i(TAG, "component=user_location seam=stream_remove class=requested")
            client.removeLocationUpdates(callback)
        }

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
    if (map == null) {
        Log.w(TAG, "component=user_location seam=recenter_map class=not_ready")
        return
    }
    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasPermission) {
        Log.w(TAG, "component=user_location seam=recenter_permission class=missing")
        return
    }

    if (cached != null) {
        Log.d(TAG, "component=user_location seam=recenter_cached class=received")
        map.animateToLatLng(cached.latitude, cached.longitude)
        return
    }
    // No fix yet — request a fresh one with feedback.
    Log.i(TAG, "component=user_location seam=recenter_request class=started")
    scope.launch { snackbarHostState.showSnackbar(locatingMessage) }
    val client = LocationServices.getFusedLocationProviderClient(context)
    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        .addOnSuccessListener { location ->
            if (location != null) {
                Log.d(TAG, "component=user_location seam=recenter_fix class=received")
                map.animateToLatLng(location.latitude, location.longitude)
            } else {
                Log.w(TAG, "component=user_location seam=recenter_fix class=empty")
                scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
            }
        }
        .addOnFailureListener {
            Log.w(TAG, "component=user_location seam=recenter_task class=failed")
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
