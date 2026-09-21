package au.com.ausroads.feature.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: NavigationLocationSource,
    private val tts: NavigationTts,
    private val routingEngine: RoutingEngine,
) : ViewModel() {

    internal constructor(
        context: Context,
        locationProvider: NavigationLocationSource,
        tts: NavigationTts,
        routingEngine: RoutingEngine,
        preciseLocationPermissionChecker: () -> Boolean,
    ) : this(context, locationProvider, tts, routingEngine) {
        this.preciseLocationPermissionChecker = preciseLocationPermissionChecker
    }

    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _batteryWarning = MutableStateFlow(false)
    val batteryWarning: StateFlow<Boolean> = _batteryWarning.asStateFlow()

    private var route: RouteResult? = null
    private var maneuvers: List<Maneuver> = emptyList()
    private var currentManeuverIndex = 0
    private var navigationJob: Job? = null
    private var locationTrackingGeneration = 0L
    private var locationTrackingRequested = false
    private var navigationHostStarted = false
    private var recalculationJob: Job? = null
    private var recalculationGeneration = 0L
    private var navigationStartTime = 0L
    private var savedDestination: GeoPoint? = null
    private var lastKnownPosition: GeoPoint? = null
    private var lastRecalcTime = 0L
    private var isRecalculating = false
    private var lastNavigatingState: NavigationState.Navigating? = null
    private var preciseLocationPermissionChecker: () -> Boolean = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Mirrors Settings.ttsEnabled. Bridged in from the UI layer (which owns the
    // settings dependency) so voice guidance actually honours the toggle.
    private var ttsEnabled = true

    /** Enable/disable spoken guidance. Called from the UI as the setting changes. */
    fun setTtsEnabled(enabled: Boolean) {
        ttsEnabled = enabled
    }

    /** Starts live navigation only when Android has granted precise location. */
    fun startNavigation(routeResult: RouteResult): Boolean {
        if (!hasPreciseLocationPermission()) {
            // Permission can be revoked between the route-sheet check and this call.
            // End any older session so its late work cannot replace this safety state.
            stopNavigation()
            _state.update { NavigationState.PreciseLocationRequired }
            return false
        }

        route = routeResult
        cancelRecalculation()
        maneuvers = routeResult.maneuvers
        currentManeuverIndex = 0
        navigationStartTime = System.currentTimeMillis()
        _batteryWarning.update { false }
        savedDestination = routeResult.geometry.lastOrNull()
        lastKnownPosition = null
        lastRecalcTime = 0L

        // Initialize TTS for voice guidance
        tts.initialize()

        val initialState = NavigationState.Navigating(
                currentManeuver = maneuvers.firstOrNull(),
                nextManeuver = maneuvers.getOrNull(1),
                remainingDistanceMeters = routeResult.distanceMeters.toDouble(),
                remainingDurationSeconds = routeResult.durationSeconds.toDouble(),
                maneuverIndex = 0,
                totalManeuvers = maneuvers.size,
            )
        lastNavigatingState = initialState
        _state.update { initialState }

        locationTrackingRequested = true
        pauseGpsTracking()
        startGpsTracking()
        return true
    }

    fun stopNavigation() {
        locationTrackingRequested = false
        pauseGpsTracking()
        cancelRecalculation()
        route = null
        maneuvers = emptyList()
        currentManeuverIndex = 0
        navigationStartTime = 0L
        savedDestination = null
        lastKnownPosition = null
        lastRecalcTime = 0L
        lastNavigatingState = null
        _batteryWarning.update { false }
        _state.update { NavigationState.Idle }
        tts.shutdown()
    }

    fun updatePosition(position: GeoPoint, speedKmh: Double) {
        lastKnownPosition = position
        val current = _state.value
        if (current !is NavigationState.Navigating) return

        // Check if we've reached the destination
        val destination = route?.geometry?.lastOrNull()
        if (destination != null) {
            val distToDest = haversineMeters(position, destination)
            if (distToDest < ARRIVAL_THRESHOLD_METERS) {
                _state.update { NavigationState.Arrived }
                if (ttsEnabled) tts.speakArrival()
                locationTrackingRequested = false
                pauseGpsTracking()
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

        val nextState = NavigationState.Navigating(
                currentManeuver = maneuvers.getOrNull(currentManeuverIndex),
                nextManeuver = maneuvers.getOrNull(currentManeuverIndex + 1),
                remainingDistanceMeters = calculateRemainingDistance(position),
                remainingDurationSeconds = calculateRemainingDuration(position),
                currentSpeedKmh = speedKmh,
                maneuverIndex = currentManeuverIndex,
                totalManeuvers = maneuvers.size,
            )
        lastNavigatingState = nextState
        _state.update { nextState }

        // Battery warning: check real battery level
        val batteryPct = getBatteryPercent()
        if (batteryPct in 0..BATTERY_WARNING_THRESHOLD) {
            _batteryWarning.update { true }
        } else if (batteryPct > BATTERY_WARNING_THRESHOLD + 5) {
            // Clear warning only if battery recovers above threshold + hysteresis
            _batteryWarning.update { false }
        }
    }

    private fun hasPreciseLocationPermission(): Boolean = preciseLocationPermissionChecker()

    /**
     * Binds the retained navigation session to its visible Compose host. The
     * session and guidance stay in memory across backgrounding, but the live
     * provider subscription exists only while that host is started.
     */
    internal fun onHostStarted() {
        if (navigationHostStarted) return
        navigationHostStarted = true
        startGpsTracking()
    }

    internal fun onHostStopped() {
        if (!navigationHostStarted) return
        navigationHostStarted = false
        pauseGpsTracking()
    }

    @Suppress("TooGenericExceptionCaught") // provider implementations report platform-specific failures
    private fun startGpsTracking() {
        if (!navigationHostStarted || !locationTrackingRequested) return
        if (navigationJob?.isActive == true) return

        val generation = ++locationTrackingGeneration
        navigationJob = viewModelScope.launch {
            try {
                locationProvider.locationUpdates(intervalMs = 1000).collect { loc ->
                    if (!canPublishLocation(generation)) return@collect
                    updatePosition(
                        GeoPoint(longitude = loc.longitude, latitude = loc.latitude),
                        loc.speedKmh,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!canPublishLocation(generation)) return@launch
                locationTrackingRequested = false
                cancelRecalculation()
                val previous = lastNavigatingState
                if (previous != null) {
                    _state.update { NavigationState.LocationUnavailable(previous) }
                }
            } finally {
                if (generation == locationTrackingGeneration) navigationJob = null
            }
        }
    }

    private fun canPublishLocation(generation: Long): Boolean =
        generation == locationTrackingGeneration &&
            navigationHostStarted &&
            locationTrackingRequested

    private fun pauseGpsTracking() {
        locationTrackingGeneration++
        val activeJob = navigationJob
        navigationJob = null
        activeJob?.cancel()
    }

    fun retryLocation() {
        val unavailable = _state.value as? NavigationState.LocationUnavailable ?: return
        if (!hasPreciseLocationPermission()) {
            _state.update { NavigationState.PreciseLocationRequired }
            return
        }
        lastNavigatingState = unavailable.previousState
        _state.update { unavailable.previousState }
        locationTrackingRequested = true
        pauseGpsTracking()
        startGpsTracking()
    }

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
        launchRecalculation(currentPos, dest, currentState)
    }

    fun retryRecalculation() {
        val failed = _state.value as? NavigationState.RecalculationFailed ?: return
        launchRecalculation(
            origin = lastKnownPosition ?: failed.lastAttemptOrigin,
            destination = failed.destination,
            previousState = failed.previousState,
        )
    }

    @Suppress("TooGenericExceptionCaught") // routing engines expose implementation-specific failures
    private fun launchRecalculation(
        origin: GeoPoint,
        destination: GeoPoint,
        previousState: NavigationState.Navigating,
    ) {
        recalculationJob?.cancel()
        val generation = ++recalculationGeneration
        isRecalculating = true
        lastRecalcTime = System.currentTimeMillis()

        _state.update {
            NavigationState.Recalculating(
                previousState = previousState,
                destination = destination,
            )
        }
        if (ttsEnabled) tts.speakText(context.getString(R.string.nav_recalculating_tts))

        recalculationJob = viewModelScope.launch {
            try {
                val result = routingEngine.computeRoute(
                    RouteRequest(
                        origin = origin,
                        destination = destination,
                        costingProfile = CostingProfile.AUTO,
                    ),
                )
                if (!ownsRecalculation(generation)) return@launch

                route = result
                maneuvers = result.maneuvers
                currentManeuverIndex = 0

                val recalculatedState = NavigationState.Navigating(
                        currentManeuver = maneuvers.firstOrNull(),
                        nextManeuver = maneuvers.getOrNull(1),
                        remainingDistanceMeters = result.distanceMeters.toDouble(),
                        remainingDurationSeconds = result.durationSeconds.toDouble(),
                        currentSpeedKmh = previousState.currentSpeedKmh,
                        maneuverIndex = 0,
                        totalManeuvers = maneuvers.size,
                    )
                lastNavigatingState = recalculatedState
                _state.update { recalculatedState }
                if (ttsEnabled) tts.speakText(context.getString(R.string.nav_recalculated_tts))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!ownsRecalculation(generation)) return@launch
                _state.update {
                    NavigationState.RecalculationFailed(
                        previousState = previousState,
                        lastAttemptOrigin = origin,
                        destination = destination,
                    )
                }
                if (ttsEnabled) tts.speakText(context.getString(R.string.nav_recalc_failed_tts))
            } finally {
                if (ownsRecalculation(generation)) {
                    isRecalculating = false
                    recalculationJob = null
                }
            }
        }
    }

    private fun ownsRecalculation(generation: Long): Boolean =
        generation == recalculationGeneration

    private fun cancelRecalculation() {
        recalculationGeneration++
        recalculationJob?.cancel()
        recalculationJob = null
        isRecalculating = false
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
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
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
