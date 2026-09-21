/*
 * withNetwork-flavor location providers — the real fused-location implementations
 * backing the blue dot and the "My Location" FAB.
 *
 * These functions (and the play-services-location dependency they need) are
 * deliberately confined to the withNetwork source set. The `offline` source set
 * provides Android LocationManager twins with identical signatures, so the
 * shared MapScreen compiles unchanged without linking Google Play Services.
 */
package au.com.ausroads.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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
        Log.i(TAG, "rememberUserLocation: hasPermission=$hasPermission")
        if (!hasPermission) {
            location.value = null
            return@DisposableEffect onDispose { }
        }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val handler = Handler(Looper.getMainLooper())
        var isStarted = false
        var currentLocationCancellation: CancellationTokenSource? = null
        var expiringFixTimeMillis: Long? = null
        val clearExpiredFix = Runnable {
            if (location.value?.time == expiringFixTimeMillis) location.value = null
        }
        fun clearLocation() {
            handler.removeCallbacks(clearExpiredFix)
            expiringFixTimeMillis = null
            location.value = null
        }
        fun cancelCurrentLocationRequest() {
            currentLocationCancellation?.cancel()
            currentLocationCancellation = null
        }
        fun publishIfFresh(
            fix: Location?,
            isLiveCallback: Boolean,
        ) {
            if (!isStarted || fix == null) return
            val receivedAtMillis = System.currentTimeMillis()
            val isPreciseLocationGranted = context.hasPreciseLocationPermission()
            val maxAgeMillis = maximumLocationFixAgeMillis(isPreciseLocationGranted)
            val displayTimeMillis = if (isLiveCallback) {
                liveLocationDisplayTimeMillis(
                    fixTimeMillis = fix.time,
                    receivedAtMillis = receivedAtMillis,
                    isPreciseLocationGranted = isPreciseLocationGranted,
                )
            } else {
                fix.time.takeIf {
                    isLocationFixFresh(
                        fixTimeMillis = it,
                        nowMillis = receivedAtMillis,
                        maxAgeMillis = maxAgeMillis,
                    )
                }
            } ?: return
            val displayFix = if (displayTimeMillis == fix.time) {
                fix
            } else {
                Location(fix).apply { time = displayTimeMillis }
            }
            val delayMillis = remainingLocationFreshnessMillis(
                fixTimeMillis = displayFix.time,
                nowMillis = receivedAtMillis,
                maxAgeMillis = maxAgeMillis,
            ) ?: return
            handler.removeCallbacks(clearExpiredFix)
            location.value = displayFix
            expiringFixTimeMillis = displayFix.time
            handler.postDelayed(clearExpiredFix, delayMillis)
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let {
                    Log.d(TAG, "location update (acc ${it.accuracy}m)")
                    publishIfFresh(it, isLiveCallback = true)
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun start() {
            if (isStarted) return
            isStarted = true
            clearLocation()
            cancelCurrentLocationRequest()
            Log.i(TAG, "starting fused location updates")
            // Seed immediately: lastLocation is fast but often null on a cold device,
            // so also request a single fresh fix so the blue dot appears promptly.
            runCatching {
                client.lastLocation
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            val maxAgeMillis = maximumLocationFixAgeMillis(
                                context.hasPreciseLocationPermission(),
                            )
                            if (
                                isLocationFixFresh(
                                    fixTimeMillis = loc.time,
                                    nowMillis = System.currentTimeMillis(),
                                    maxAgeMillis = maxAgeMillis,
                                )
                            ) {
                                Log.d(TAG, "lastLocation accepted (acc ${loc.accuracy}m)")
                                publishIfFresh(loc, isLiveCallback = false)
                            } else {
                                Log.i(TAG, "lastLocation was stale; waiting for a fresh fix")
                            }
                        } else {
                            Log.i(TAG, "lastLocation returned null (no cached fix)")
                        }
                    }
                    .addOnFailureListener { Log.w(TAG, "lastLocation failed", it) }
            }.onFailure { Log.w(TAG, "lastLocation call threw", it) }
            val cancellation = CancellationTokenSource()
            currentLocationCancellation = cancellation
            runCatching {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                    .addOnSuccessListener { loc ->
                        if (currentLocationCancellation !== cancellation) {
                            return@addOnSuccessListener
                        }
                        currentLocationCancellation = null
                        if (loc != null) {
                            Log.d(TAG, "getCurrentLocation returned (acc ${loc.accuracy}m)")
                            publishIfFresh(loc, isLiveCallback = true)
                        } else {
                            Log.i(TAG, "getCurrentLocation returned null")
                        }
                    }
                    .addOnFailureListener {
                        if (currentLocationCancellation !== cancellation) {
                            return@addOnFailureListener
                        }
                        currentLocationCancellation = null
                        Log.w(TAG, "getCurrentLocation failed", it)
                    }
            }.onFailure {
                if (currentLocationCancellation === cancellation) {
                    currentLocationCancellation = null
                    cancellation.cancel()
                }
                Log.w(TAG, "getCurrentLocation call threw", it)
            }
            runCatching {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            }.onFailure { Log.w(TAG, "requestLocationUpdates threw", it) }
        }
        fun stop() {
            isStarted = false
            cancelCurrentLocationRequest()
            clearLocation()
            Log.i(TAG, "stopping fused location updates")
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
@Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
internal fun recenterOnUser(
    map: MapLibreMap?,
    context: Context,
    cached: Location?,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    locatingMessage: String,
    unavailableMessage: String,
    outsideCoverageMessage: String,
    manageMapPackLabel: String,
    onManageMapPack: () -> Unit,
    isInsideMapCoverage: (Double, Double) -> Boolean,
): RecenterRequest {
    if (map == null || !context.hasForegroundLocationPermission()) {
        Log.w(TAG, "recenter: map not ready or foreground location permission unavailable")
        return NoOpRecenterRequest
    }

    val maxAgeMillis = maximumLocationFixAgeMillis(context.hasPreciseLocationPermission())
    if (
        cached != null && isLocationFixFresh(
            fixTimeMillis = cached.time,
            nowMillis = System.currentTimeMillis(),
            maxAgeMillis = maxAgeMillis,
        )
    ) {
        Log.d(TAG, "recenter: using fresh cached fix")
        if (isInsideMapCoverage(cached.latitude, cached.longitude)) {
            map.animateToLatLng(cached.latitude, cached.longitude)
        } else {
            showOutsideCoverageRecovery(
                scope,
                snackbarHostState,
                outsideCoverageMessage,
                manageMapPackLabel,
                onManageMapPack,
            )
        }
        return NoOpRecenterRequest
    }
    // No fix yet — request a fresh one with feedback.
    Log.i(TAG, "recenter: no cached fix, requesting fresh getCurrentLocation")
    scope.launch { snackbarHostState.showSnackbar(locatingMessage) }
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancellation = CancellationTokenSource()
    var active = true
    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
        .addOnSuccessListener { location ->
            if (!active) return@addOnSuccessListener
            active = false
            if (
                location != null && isLocationFixFresh(
                    fixTimeMillis = location.time,
                    nowMillis = System.currentTimeMillis(),
                    maxAgeMillis = maxAgeMillis,
                )
            ) {
                Log.d(TAG, "recenter: got fresh fix")
                if (isInsideMapCoverage(location.latitude, location.longitude)) {
                    map.animateToLatLng(location.latitude, location.longitude)
                } else {
                    showOutsideCoverageRecovery(
                        scope,
                        snackbarHostState,
                        outsideCoverageMessage,
                        manageMapPackLabel,
                        onManageMapPack,
                    )
                }
            } else {
                Log.w(TAG, "recenter: getCurrentLocation returned no fresh fix")
                scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
            }
        }
        .addOnFailureListener {
            if (!active) return@addOnFailureListener
            active = false
            Log.w(TAG, "recenter: getCurrentLocation failed", it)
            scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
        }
    return RecenterRequest {
        if (active) {
            active = false
            cancellation.cancel()
            Log.d(TAG, "fused recenter request cancelled by screen owner")
        }
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

private fun showOutsideCoverageRecovery(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    message: String,
    actionLabel: String,
    onManageMapPack: () -> Unit,
) {
    scope.launch {
        if (snackbarHostState.showSnackbar(message, actionLabel) == SnackbarResult.ActionPerformed) {
            onManageMapPack()
        }
    }
}
