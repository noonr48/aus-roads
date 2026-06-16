package au.com.ausroads.feature.navigation

import android.os.BatteryManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.navigation.tts.NavigationTts
import au.com.ausroads.routing.engine.CostingProfile
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.min

@HiltViewModel
class NavigationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: NavigationLocationSource,
    private val tts: NavigationTts,
    private val routingEngine: RoutingEngine,
) : ViewModel() {

    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _batteryWarning = MutableStateFlow(false)
    val batteryWarning: StateFlow<Boolean> = _batteryWarning.asStateFlow()

    private var route: RouteResult? = null
    private var maneuvers: List<Maneuver> = emptyList()
    private var currentManeuverIndex = 0
    private var navigationJob: Job? = null
    private var navigationStartTime = 0L
    private var savedDestination: GeoPoint? = null
    private var lastRecalcTime = 0L
    private var isRecalculating = false

    // Mirrors Settings.ttsEnabled. Bridged in from the UI layer (which owns the
    // settings dependency) so voice guidance actually honours the toggle.
    private var ttsEnabled = true

    /** Enable/disable spoken guidance. Called from the UI as the setting changes. */
    fun setTtsEnabled(enabled: Boolean) {
        ttsEnabled = enabled
    }

    fun startNavigation(routeResult: RouteResult) {
        route = routeResult
        maneuvers = routeResult.maneuvers
        currentManeuverIndex = 0
        navigationStartTime = System.currentTimeMillis()
        _batteryWarning.update { false }
        savedDestination = routeResult.geometry.lastOrNull()
        lastRecalcTime = 0L
        isRecalculating = false

        // Initialize TTS for voice guidance
        tts.initialize()

        _state.update {
            NavigationState.Navigating(
                currentManeuver = maneuvers.firstOrNull(),
                nextManeuver = maneuvers.getOrNull(1),
                remainingDistanceMeters = routeResult.distanceMeters.toDouble(),
                remainingDurationSeconds = routeResult.durationSeconds.toDouble(),
                maneuverIndex = 0,
                totalManeuvers = maneuvers.size,
            )
        }

        if (hasLocationPermission()) {
            startGpsTracking()
        } else {
            startSimulatedTracking()
        }
    }

    fun stopNavigation() {
        navigationJob?.cancel()
        navigationJob = null
        route = null
        maneuvers = emptyList()
        currentManeuverIndex = 0
        navigationStartTime = 0L
        savedDestination = null
        lastRecalcTime = 0L
        isRecalculating = false
        _batteryWarning.update { false }
        _state.update { NavigationState.Idle }
        tts.shutdown()
    }

    fun updatePosition(position: GeoPoint, speedKmh: Double) {
        val current = _state.value
        if (current !is NavigationState.Navigating) return

        // Check if we've reached the destination
        val destination = route?.geometry?.lastOrNull()
        if (destination != null) {
            val distToDest = haversineMeters(position, destination)
            if (distToDest < ARRIVAL_THRESHOLD_METERS) {
                _state.update { NavigationState.Arrived }
                if (ttsEnabled) tts.speakArrival()
                navigationJob?.cancel()
                return
            }
        }

        // Off-route detection
        val nearest = findNearestRoutePoint(position)
        if (nearest != null && nearest.distanceMeters > OFF_ROUTE_THRESHOLD_METERS) {
            val now = System.currentTimeMillis()
            if (!isRecalculating && (now - lastRecalcTime) > RECALC_COOLDOWN_MS) {
                triggerRecalculation(position, current)
            }
            return
        }

        // Advance maneuver if close enough
        val nextManeuverPoint = getNextManeuverPoint()
        if (nextManeuverPoint != null) {
            val distToManeuver = haversineMeters(position, nextManeuverPoint)
            if (distToManeuver < MANEUVER_ADVANCE_METERS) {
                currentManeuverIndex++
                // Speak the new maneuver instruction
                val newManeuver = maneuvers.getOrNull(currentManeuverIndex)
                if (newManeuver != null && ttsEnabled) {
                    tts.speakManeuver(newManeuver.instruction, distToManeuver)
                }
            }
        }

        _state.update {
            NavigationState.Navigating(
                currentManeuver = maneuvers.getOrNull(currentManeuverIndex),
                nextManeuver = maneuvers.getOrNull(currentManeuverIndex + 1),
                remainingDistanceMeters = calculateRemainingDistance(position),
                remainingDurationSeconds = calculateRemainingDuration(position),
                currentSpeedKmh = speedKmh,
                maneuverIndex = currentManeuverIndex,
                totalManeuvers = maneuvers.size,
            )
        }

        // Battery warning: check real battery level
        val batteryPct = getBatteryPercent()
        if (batteryPct in 0..BATTERY_WARNING_THRESHOLD) {
            _batteryWarning.update { true }
        } else if (batteryPct > BATTERY_WARNING_THRESHOLD + 5) {
            // Clear warning only if battery recovers above threshold + hysteresis
            _batteryWarning.update { false }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startGpsTracking() {
        navigationJob = viewModelScope.launch {
            locationProvider.locationUpdates(intervalMs = 1000)
                .catch {
                    // Location permission can be revoked mid-navigation (the provider
                    // then throws SecurityException). Catch it so the collector doesn't
                    // crash the app; navigation holds its last known position.
                }
                .collect { loc ->
                    updatePosition(
                        GeoPoint(longitude = loc.longitude, latitude = loc.latitude),
                        loc.speedKmh,
                    )
                }
        }
    }

    private fun startSimulatedTracking() {
        navigationJob = viewModelScope.launch {
            val geom = route?.geometry
            if (geom.isNullOrEmpty()) return@launch
            var simIndex = 0
            val stepIntervalMs = 2000L // Move every 2 seconds
            while (simIndex < geom.size - 1) {
                delay(stepIntervalMs)
                simIndex = min(simIndex + 1, geom.size - 1)
                val simPos = geom[simIndex]
                // Estimate speed from remaining route data
                val remainingDist = calculateRemainingDistanceFromIndex(simIndex)
                val remainingDuration = route?.durationSeconds?.toDouble()
                    ?.let { it * (remainingDist / (route?.distanceMeters?.toDouble() ?: 1.0)) }
                    ?: (remainingDist / 1000.0 / 60.0 * 3600.0)
                val speedKmh = if (remainingDuration > 0) {
                    (remainingDist / 1000.0) / (remainingDuration / 3600.0)
                } else {
                    60.0
                }
                updatePosition(simPos, speedKmh.coerceIn(1.0, 120.0))
            }
        }
    }

    private fun calculateRemainingDistanceFromIndex(fromIndex: Int): Double =
        NavigationGeometry.distanceFromIndex(route?.geometry.orEmpty(), fromIndex)

    private fun getNextManeuverPoint(): GeoPoint? {
        val geom = route?.geometry ?: return null
        val maneuver = maneuvers.getOrNull(currentManeuverIndex + 1) ?: return null
        val idx = maneuver.beginShapeIndex.coerceIn(0, geom.size - 1)
        return geom.getOrNull(idx)
    }

    private fun calculateRemainingDistance(currentPos: GeoPoint): Double =
        NavigationGeometry.remainingDistanceMeters(route?.geometry.orEmpty(), currentPos)

    private fun findNearestRoutePoint(pos: GeoPoint): NavigationGeometry.NearestRoutePoint? =
        NavigationGeometry.nearestRoutePoint(route?.geometry.orEmpty(), pos)

    private fun triggerRecalculation(currentPos: GeoPoint, currentState: NavigationState.Navigating) {
        val dest = savedDestination ?: return
        isRecalculating = true
        lastRecalcTime = System.currentTimeMillis()

        _state.update {
            NavigationState.Recalculating(
                previousState = currentState,
                destination = dest,
            )
        }
        if (ttsEnabled) tts.speakText(context.getString(R.string.nav_recalculating_tts))

        viewModelScope.launch {
            try {
                val result = routingEngine.computeRoute(
                    RouteRequest(
                        origin = currentPos,
                        destination = dest,
                        costingProfile = CostingProfile.AUTO,
                    ),
                )
                route = result
                maneuvers = result.maneuvers
                currentManeuverIndex = 0

                _state.update {
                    NavigationState.Navigating(
                        currentManeuver = maneuvers.firstOrNull(),
                        nextManeuver = maneuvers.getOrNull(1),
                        remainingDistanceMeters = result.distanceMeters.toDouble(),
                        remainingDurationSeconds = result.durationSeconds.toDouble(),
                        currentSpeedKmh = currentState.currentSpeedKmh,
                        maneuverIndex = 0,
                        totalManeuvers = maneuvers.size,
                    )
                }
                if (ttsEnabled) tts.speakText(context.getString(R.string.nav_recalculated_tts))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { currentState }
                if (ttsEnabled) tts.speakText(context.getString(R.string.nav_recalc_failed_tts))
            } finally {
                isRecalculating = false
            }
        }
    }

    private fun calculateRemainingDuration(currentPos: GeoPoint): Double {
        val remainingDist = calculateRemainingDistance(currentPos)
        val routeDuration = route?.durationSeconds?.toDouble() ?: 0.0
        val routeDistance = route?.distanceMeters?.toDouble() ?: 1.0
        // Scale the total route duration by the fraction of distance remaining
        return if (routeDistance > 0) {
            routeDuration * (remainingDist / routeDistance)
        } else {
            // Fallback: assume 60 km/h
            (remainingDist / 1000.0) / 60.0 * 3600.0
        }
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double =
        NavigationGeometry.haversineMeters(a, b)

    private fun getBatteryPercent(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } else {
                val intent = context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                )
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            }
        } catch (_: Exception) {
            -1 // Unknown
        }
    }

    companion object {
        const val ARRIVAL_THRESHOLD_METERS = 50.0
        const val MANEUVER_ADVANCE_METERS = 30.0
        const val BATTERY_WARNING_THRESHOLD = 20 // Warn when battery drops below 20%
        const val OFF_ROUTE_THRESHOLD_METERS = 100.0 // Trigger re-route when >100m from route
        const val RECALC_COOLDOWN_MS = 15_000L // Debounce re-route by 15 seconds
    }
}
