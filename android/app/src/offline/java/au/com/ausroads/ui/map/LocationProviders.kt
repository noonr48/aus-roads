/*
 * No-network location provider.
 *
 * Foreground GPS is an on-device capability and does not require INTERNET or
 * Google Play Services. This flavor therefore uses Android LocationManager for
 * the same blue-dot/recenter contract as the network flavor while retaining a
 * hard no-network manifest.
 */
package au.com.ausroads.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

private const val MY_LOCATION_ZOOM = 15.0
private const val LOCATION_INTERVAL_MS = 2_000L
private const val TAG = "UserLocation"

@Composable
internal fun rememberUserLocation(hasPermission: Boolean): State<Location?> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val location = remember { mutableStateOf<Location?>(null) }

    DisposableEffect(context, hasPermission, lifecycleOwner) {
        Log.i(TAG, "native location stream: hasPermission=$hasPermission")
        if (!hasPermission) {
            location.value = null
            return@DisposableEffect onDispose { }
        }

        val manager = context.getSystemService(LocationManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var isStarted = false
        var expiringFixTimeMillis: Long? = null
        val clearExpiredFix = Runnable {
            if (location.value?.time == expiringFixTimeMillis) location.value = null
        }
        fun publishFresh(
            fix: Location?,
            maxAgeMillis: Long,
        ) {
            handler.removeCallbacks(clearExpiredFix)
            val delayMillis = fix?.let {
                remainingLocationFreshnessMillis(
                    fixTimeMillis = it.time,
                    nowMillis = System.currentTimeMillis(),
                    maxAgeMillis = maxAgeMillis,
                )
            }
            if (fix == null || delayMillis == null) {
                expiringFixTimeMillis = null
                location.value = null
                return
            }
            location.value = fix
            expiringFixTimeMillis = fix.time
            handler.postDelayed(clearExpiredFix, delayMillis)
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(fix: Location) {
                Log.d(TAG, "native location update (acc ${fix.accuracy}m)")
                val receivedAtMillis = System.currentTimeMillis()
                val isPreciseLocationGranted = context.hasPreciseLocationPermission()
                val displayTimeMillis = liveLocationDisplayTimeMillis(
                    fixTimeMillis = fix.time,
                    receivedAtMillis = receivedAtMillis,
                    isPreciseLocationGranted = isPreciseLocationGranted,
                ) ?: return
                val displayFix = if (displayTimeMillis == fix.time) {
                    fix
                } else {
                    Location(fix).apply { time = displayTimeMillis }
                }
                publishFresh(
                    fix = displayFix,
                    maxAgeMillis = maximumLocationFixAgeMillis(isPreciseLocationGranted),
                )
            }

            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        @SuppressLint("MissingPermission")
        fun start() {
            if (isStarted) return
            isStarted = true
            val providers = manager.usableProviders()
            val maxAgeMillis = maximumLocationFixAgeMillis(context.hasPreciseLocationPermission())
            publishFresh(
                manager.bestFreshLastKnownLocation(
                    providers = providers,
                    nowMillis = System.currentTimeMillis(),
                    maxAgeMillis = maxAgeMillis,
                ),
                maxAgeMillis = maxAgeMillis,
            )
            providers.forEach { provider ->
                runCatching {
                    manager.requestLocationUpdates(
                        provider,
                        LOCATION_INTERVAL_MS,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                }.onFailure { Log.w(TAG, "native updates failed for $provider", it) }
            }
            Log.i(TAG, "started native location updates (${providers.joinToString()})")
        }

        fun stop() {
            isStarted = false
            runCatching { manager.removeUpdates(listener) }
            handler.removeCallbacks(clearExpiredFix)
            Log.i(TAG, "stopped native location updates")
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
            location.value = null
        }
    }
    return location
}

@SuppressLint("MissingPermission")
@Suppress("LongParameterList", "LongMethod", "DEPRECATION", "CognitiveComplexMethod")
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
    if (map == null || !context.hasForegroundLocationPermission()) return NoOpRecenterRequest
    val manager = context.getSystemService(LocationManager::class.java)
    val providers = manager.usableProviders()
    val nowMillis = System.currentTimeMillis()
    val isPreciseLocationGranted = context.hasPreciseLocationPermission()
    val maxAgeMillis = maximumLocationFixAgeMillis(isPreciseLocationGranted)
    val immediate = cached
        ?.takeIf { fix ->
            isLocationFixFresh(fix.time, nowMillis, maxAgeMillis = maxAgeMillis)
        }
        ?: manager.bestFreshLastKnownLocation(providers, nowMillis, maxAgeMillis)
    if (immediate != null) {
        if (isInsideMapCoverage(immediate.latitude, immediate.longitude)) {
            map.animateToLatLng(immediate.latitude, immediate.longitude)
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

    if (providers.isEmpty()) {
        scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
        return NoOpRecenterRequest
    }
    scope.launch { snackbarHostState.showSnackbar(locatingMessage) }
    val handler = Handler(Looper.getMainLooper())
    val requestStartedAtMillis = System.currentTimeMillis()
    var completed = false
    val listener = object : LocationListener {
        override fun onLocationChanged(fix: Location) {
            if (completed) return
            val now = System.currentTimeMillis()
            if (
                !isLocationFixFresh(
                    fixTimeMillis = fix.time,
                    nowMillis = now,
                    minimumFixTimeMillis = if (isPreciseLocationGranted) {
                        requestStartedAtMillis
                    } else {
                        0L
                    },
                    maxAgeMillis = maxAgeMillis,
                )
            ) {
                Log.d(TAG, "ignoring stale native recenter callback")
                return
            }
            completed = true
            manager.removeUpdates(this)
            handler.removeCallbacksAndMessages(this)
            if (isInsideMapCoverage(fix.latitude, fix.longitude)) {
                map.animateToLatLng(fix.latitude, fix.longitude)
            } else {
                showOutsideCoverageRecovery(
                    scope,
                    snackbarHostState,
                    outsideCoverageMessage,
                    manageMapPackLabel,
                    onManageMapPack,
                )
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }
    val started = providers.count { provider ->
        runCatching {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }.onFailure { error ->
            Log.w(TAG, "native recenter request failed for $provider", error)
        }.isSuccess
    }
    return if (started == 0) {
        completed = true
        manager.removeUpdates(listener)
        scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
        NoOpRecenterRequest
    } else {
        handler.postAtTime({
            if (!completed) {
                completed = true
                manager.removeUpdates(listener)
                scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
            }
        }, listener, android.os.SystemClock.uptimeMillis() + LOCATION_TIMEOUT_MS)
        RecenterRequest {
            if (!completed) {
                completed = true
                manager.removeUpdates(listener)
                handler.removeCallbacksAndMessages(listener)
                Log.d(TAG, "native recenter request cancelled by screen owner")
            }
        }
    }
}

private fun LocationManager.usableProviders(): List<String> =
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .filter { provider -> runCatching { isProviderEnabled(provider) }.getOrDefault(false) }

@SuppressLint("MissingPermission")
private fun LocationManager.bestFreshLastKnownLocation(
    providers: List<String>,
    nowMillis: Long,
    maxAgeMillis: Long,
): Location? = selectBestFreshFix(
    candidates = providers.mapNotNull { provider ->
        runCatching { getLastKnownLocation(provider) }.getOrNull()
    },
    nowMillis = nowMillis,
    fixTimeMillis = Location::getTime,
    accuracyMeters = { fix -> if (fix.hasAccuracy()) fix.accuracy else null },
    maxAgeMillis = maxAgeMillis,
)

private fun MapLibreMap.animateToLatLng(latitude: Double, longitude: Double) {
    animateCamera(
        CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), MY_LOCATION_ZOOM),
        1_000,
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

private const val LOCATION_TIMEOUT_MS = 10_000L
