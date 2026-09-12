package au.com.ausroads.feature.navigation

import android.os.BatteryManager
import android.os.SystemClock
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
import au.com.ausroads.feature.trip.ProximityEngine
import au.com.ausroads.feature.trip.ProximityEvent
import au.com.ausroads.feature.trip.ProximityTarget
import au.com.ausroads.feature.trip.SpeedAlert
import au.com.ausroads.feature.trip.SpeedLimitMonitor
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: NavigationLocationSource,
    private val tts: NavigationTts,
    private val routingEngine: RoutingEngine,
    private val roadHazards: RoadHazardSource,
) : ViewModel() {

    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _batteryWarning = MutableStateFlow(false)
    val batteryWarning: StateFlow<Boolean> = _batteryWarning.asStateFlow()

    private var route: RouteResult? = null
    private var maneuvers: List<Maneuver> = emptyList()
    private var currentManeuverIndex = 0
    private var navigationJob: Job? = null
    private var recalcJob: Job? = null
    private var navigationStartTime = 0L
    private var savedDestination: GeoPoint? = null
    private var lastRecalcTime = 0L
    private var isRecalculating = false

    // Mirrors Settings.ttsEnabled. Bridged in from the UI layer (which owns the
    // settings dependency) so voice guidance actually honours the toggle.
    private var ttsEnabled = true

    // --- Driver-assist state (speed limit + speed cameras) ------------------
    //
    // Road context refreshes are throttled by distance/time; between refreshes
    // the overspeed classification and camera proximity run against the cached
    // limit + camera set. Camera announcements latch per camera id so a camera
    // is spoken at most once per session while it stays in range.
    private val speedMonitor = SpeedLimitMonitor(approachingMarginKmh = 0.0, overBufferKmh = 5.0)
    private val cameraProximity = ProximityEngine(exitSlack = CAMERA_EXIT_SLACK)
    private val cameraById = mutableMapOf<String, RoadHazardCamera>()
    private val announcedCameras = mutableSetOf<String>()
    private var warnedCameraId: String? = null
    private var currentSpeedLimitKmh: Int? = null
    private var lastHazardQueryPosition: GeoPoint? = null
    private var lastHazardQueryTime = 0L
    private var hazardJob: Job? = null

    // GPS health: first-fix signalling + staleness watchdog.
    private var lastFixElapsedRealtime = 0L
    private var gpsWatchdogJob: Job? = null
    private var gpsRetryCount = 0

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

        // Reset driver-assist state for the new session.
        speedMonitor.reset()
        cameraById.clear()
        announcedCameras.clear()
        warnedCameraId = null
        currentSpeedLimitKmh = null
        lastHazardQueryPosition = null
        lastHazardQueryTime = 0L
        hazardJob?.cancel()
        hazardJob = null
        lastFixElapsedRealtime = 0L
        gpsRetryCount = 0
        // A fresh session must never inherit a stale reroute coroutine.
        recalcJob?.cancel()
        recalcJob = null
        navigationJob?.cancel()
        navigationJob = null

        if (!hasLocationPermission()) {
            // Privacy contract (docs/notes/privacy.md v0.7): "the app functions
            // without it but navigation mode is unavailable." Without FINE
            // location there are no position samples, so entering Navigating
            // would fabricate progress toward arrival -- and the offline flavor
            // strips that permission entirely. Refuse BEFORE publishing
            // Navigating so downstream observers cannot latch a phantom session.
            _state.update { NavigationState.LocationUnavailable }
            return
        }

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
                awaitingGpsFix = true,
            )
        }

        startGpsTracking()
        startGpsWatchdog()
    }

    /** Dismiss the [NavigationState.LocationUnavailable] banner back to Idle. */
    fun dismissLocationUnavailable() {
        if (_state.value is NavigationState.LocationUnavailable) {
            _state.update { NavigationState.Idle }
        }
    }

    fun stopNavigation() {
        navigationJob?.cancel()
        navigationJob = null
        recalcJob?.cancel()
        recalcJob = null
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = null
        hazardJob?.cancel()
        hazardJob = null
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

    fun updatePosition(position: GeoPoint, speedKmh: Double, bearingDegrees: Float = 0f) {
        val current = _state.value
        if (current !is NavigationState.Navigating) return

        lastFixElapsedRealtime = SystemClock.elapsedRealtime()

        // Check if we've reached the destination
        val destination = route?.geometry?.lastOrNull()
        if (destination != null) {
            val distToDest = haversineMeters(position, destination)
            if (distToDest < ARRIVAL_THRESHOLD_METERS) {
                _state.update { NavigationState.Arrived }
                if (ttsEnabled) tts.speakArrival()
                navigationJob?.cancel()
                gpsWatchdogJob?.cancel()
                return
            }
        }

        // Off-route detection
        val nearest = findNearestRoutePoint(position)
        if (nearest != null && nearest.distanceMeters > OFF_ROUTE_THRESHOLD_METERS) {
            val now = System.currentTimeMillis()
            if (!isRecalculating && (now - lastRecalcTime) > RECALC_COOLDOWN_MS) {
                triggerRecalculation(position, current)
                return
            }
        }

        // Advance maneuver if close enough
        val nextManeuverPoint = getNextManeuverPoint()
        var nextManeuverDistance: Double? = null
        if (nextManeuverPoint != null) {
            val distToManeuver = haversineMeters(position, nextManeuverPoint)
            nextManeuverDistance = distToManeuver
            if (distToManeuver < MANEUVER_ADVANCE_METERS) {
                currentManeuverIndex++
                // Speak the new maneuver instruction
                val newManeuver = maneuvers.getOrNull(currentManeuverIndex)
                if (newManeuver != null && ttsEnabled) {
                    tts.speakManeuver(newManeuver.instruction, distToManeuver)
                }
            }
        }

        // Refresh road context (speed limit + cameras) when stale.
        maybeRefreshRoadHazards(position)

        // Camera proximity: Enter/Exit events with hysteresis.
        val cameraWarning = processCameraProximity(position, speedKmh, bearingDegrees)

        // Over-speed classification with hysteresis.
        val speedAlert = speedMonitor.update(speedKmh, currentSpeedLimitKmh)
        val isOverspeeding = speedAlert == SpeedAlert.OVER

        _state.update {
            NavigationState.Navigating(
                currentManeuver = maneuvers.getOrNull(currentManeuverIndex),
                nextManeuver = maneuvers.getOrNull(currentManeuverIndex + 1),
                remainingDistanceMeters = calculateRemainingDistance(position),
                remainingDurationSeconds = calculateRemainingDuration(position),
                currentSpeedKmh = speedKmh,
                speedLimitKmh = currentSpeedLimitKmh,
                isOverspeeding = isOverspeeding,
                maneuverIndex = currentManeuverIndex,
                totalManeuvers = maneuvers.size,
                nextManeuverDistanceMeters = nextManeuverDistance,
                awaitingGpsFix = false,
                gpsLost = false,
                cameraWarning = cameraWarning,
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

    /**
     * Refresh the cached road context when the position has moved far enough or
     * the last query is old. Runs asynchronously; results land in the cached
     * fields and surface on the next position update.
     */
    private fun maybeRefreshRoadHazards(position: GeoPoint) {
        val now = System.currentTimeMillis()
        val lastPos = lastHazardQueryPosition
        val movedFar = lastPos == null ||
            haversineMeters(lastPos, position) >= HAZARD_REFRESH_DISTANCE_METERS
        val stale = now - lastHazardQueryTime >= HAZARD_REFRESH_INTERVAL_MS
        if (!movedFar && !stale) return
        if (hazardJob?.isActive == true) return

        lastHazardQueryPosition = position
        lastHazardQueryTime = now
        hazardJob = viewModelScope.launch {
            try {
                val ctx = roadHazards.contextAt(
                    latitude = position.latitude,
                    longitude = position.longitude,
                    radiusMeters = HAZARD_QUERY_RADIUS_METERS,
                )
                currentSpeedLimitKmh = ctx.speedLimitKmh
                cameraById.putAll(ctx.cameras.associateBy { it.id })
                // Prune cameras that dropped out of the refreshed window so the
                // adoption scan cannot resurrect ghosts of an old position.
                cameraById.keys.retainAll(ctx.cameras.mapTo(mutableSetOf()) { it.id })
                cameraProximity.setTargets(
                    ctx.cameras.map {
                        ProximityTarget(
                            id = it.id,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            radiusMeters = CAMERA_WARNING_RADIUS_METERS,
                        )
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Pack lookups must never kill navigation: keep the previous
                // context and retry on the next refresh window.
            }
        }
    }

    /**
     * Feed the position to the proximity engine and maintain the active
     * [CameraWarning]. Announces each camera at most once per session via TTS.
     */
    private fun processCameraProximity(
        position: GeoPoint,
        speedKmh: Double,
        bearingDegrees: Float,
    ): CameraWarning? {
        val events = cameraProximity.update(position.latitude, position.longitude)
        for (event in events) {
            when (event) {
                is ProximityEvent.Enter -> {
                    val camera = cameraById[event.targetId] ?: continue
                    if (event.targetId in announcedCameras) continue
                    if (!isAheadOrStationary(position, bearingDegrees, camera, speedKmh)) continue
                    announcedCameras.add(event.targetId)
                    if (ttsEnabled) {
                        tts.speakText(
                            if (camera.maxspeedKmh != null) {
                                context.getString(
                                    R.string.nav_camera_ahead_limit_tts, camera.maxspeedKmh,
                                )
                            } else {
                                context.getString(R.string.nav_camera_ahead_tts)
                            },
                        )
                    }
                }
                is ProximityEvent.Exit -> {
                    // Announcements do not repeat while in range; nothing to do.
                }
            }
        }

        // Maintain the displayed warning against the current warned camera.
        val warned = warnedCameraId?.let { cameraById[it] }
        return if (warned != null) {
            val distance = haversineMeters(position, warned.toGeoPoint())
            if (distance <= CAMERA_WARNING_RADIUS_METERS * CAMERA_EXIT_SLACK) {
                warnedCameraId = warned.id
                CameraWarning(
                    id = warned.id,
                    latitude = warned.latitude,
                    longitude = warned.longitude,
                    maxspeedKmh = warned.maxspeedKmh,
                    wayName = warned.wayName,
                    distanceMeters = distance,
                )
            } else {
                warnedCameraId = null
                null
            }
        } else {
            // No warned camera yet: adopt the nearest entered camera. The
            // isInside gate is load-bearing: without it every post-pass fix
            // re-adopts an already-passed camera and the banner flaps at 1 Hz.
            val nearest = cameraById.values
                .filter { it.id in announcedCameras && cameraProximity.isInside(it.id) }
                .minByOrNull { haversineMeters(position, it.toGeoPoint()) }
            if (nearest != null) {
                val distance = haversineMeters(position, nearest.toGeoPoint())
                warnedCameraId = nearest.id
                CameraWarning(
                    id = nearest.id,
                    latitude = nearest.latitude,
                    longitude = nearest.longitude,
                    maxspeedKmh = nearest.maxspeedKmh,
                    wayName = nearest.wayName,
                    distanceMeters = distance,
                )
            } else {
                null
            }
        }
    }

    private fun RoadHazardCamera.toGeoPoint(): GeoPoint =
        GeoPoint(longitude = longitude, latitude = latitude)

    /**
     * Suppress warnings for cameras behind the driver: at driving speed a
     * camera only counts when roughly ahead of the current bearing. When
     * stationary or nearly so (bearing is noise), any direction counts.
     */
    private fun isAheadOrStationary(
        position: GeoPoint,
        bearingDegrees: Float,
        camera: RoadHazardCamera,
        speedKmh: Double,
    ): Boolean {
        if (speedKmh < MOVING_SPEED_KMH) return true
        val toCamera = bearingTo(position, camera.toGeoPoint())
        val diff = Math.abs(normalizeAngleDelta(toCamera - bearingDegrees))
        return diff <= CAMERA_AHEAD_ARC_DEGREES
    }

    private fun normalizeAngleDelta(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped > 180.0) wrapped - 360.0 else if (wrapped < -180.0) wrapped + 360.0 else wrapped
    }

    /** Initial great-circle bearing from [from] to [to], degrees clockwise from north. */
    private fun bearingTo(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = Math.sin(deltaLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Collect the location flow with bounded retry. An upstream failure (e.g.
     * Play Services unavailable, permission revoked mid-drive) previously ended
     * the collector silently while state stayed Navigating forever; now the
     * collection is re-attempted with backoff and, when retries are exhausted,
     * the stale-fix watchdog surfaces the loss instead of frozen numbers.
     */
    private fun startGpsTracking() {
        navigationJob = viewModelScope.launch {
            while (isActive && _state.value is NavigationState.Navigating ||
                _state.value is NavigationState.Recalculating
            ) {
                try {
                    locationProvider.locationUpdates(intervalMs = 1000)
                        .catch {
                            // Swallow upstream failures here; the surrounding
                            // loop retries with backoff instead of dying.
                        }
                        .collect { loc ->
                            gpsRetryCount = 0
                            updatePosition(
                                GeoPoint(longitude = loc.longitude, latitude = loc.latitude),
                                loc.speedKmh,
                                loc.bearing,
                            )
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Fall through to bounded retry.
                }
                if (!isActive ||
                    (_state.value !is NavigationState.Navigating &&
                        _state.value !is NavigationState.Recalculating)
                ) break
                gpsRetryCount++
                if (gpsRetryCount > MAX_GPS_RETRIES) break
                delay(GPS_RETRY_BASE_MS * gpsRetryCount)
            }
        }
    }

    /**
     * Watchdog: while Navigating, flag fixes as lost when none arrived within
     * the staleness window (cold start excluded via [NavigationState.awaitingGpsFix]).
     */
    private fun startGpsWatchdog() {
        gpsWatchdogJob?.cancel()
        gpsWatchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(GPS_WATCHDOG_INTERVAL_MS)
                val s = _state.value
                if (s is NavigationState.Navigating && lastFixElapsedRealtime > 0L) {
                    val stale = System.currentTimeMillis() - lastFixElapsedRealtime > GPS_STALE_MS
                    if (stale != s.gpsLost) {
                        _state.update {
                            if (it is NavigationState.Navigating) it.copy(gpsLost = stale) else it
                        }
                    }
                }
            }
        }
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
        isRecalculating = true
        lastRecalcTime = System.currentTimeMillis()

        _state.update {
            NavigationState.Recalculating(
                previousState = currentState,
                destination = dest,
            )
        }
        if (ttsEnabled) tts.speakText(context.getString(R.string.nav_recalculating_tts))

        recalcJob = viewModelScope.launch {
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

                // Publish only if a stale reroute did not outlive its session:
                // stopNavigation()/a newer reroute may already have moved on.
                if (_state.value is NavigationState.Recalculating) {
                    _state.update {
                        NavigationState.Navigating(
                        currentManeuver = maneuvers.firstOrNull(),
                        nextManeuver = maneuvers.getOrNull(1),
                        remainingDistanceMeters = result.distanceMeters.toDouble(),
                        remainingDurationSeconds = result.durationSeconds.toDouble(),
                        currentSpeedKmh = currentState.currentSpeedKmh,
                        speedLimitKmh = currentState.speedLimitKmh,
                        isOverspeeding = currentState.isOverspeeding,
                        maneuverIndex = 0,
                        totalManeuvers = maneuvers.size,
                        awaitingGpsFix = false,
                        gpsLost = false,
                        cameraWarning = currentState.cameraWarning,
                        )
                    }
                }
                if (ttsEnabled && _state.value is NavigationState.Navigating) {
                    tts.speakText(context.getString(R.string.nav_recalculated_tts))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (_state.value is NavigationState.Recalculating) {
                    _state.update { currentState }
                }
                if (ttsEnabled && _state.value is NavigationState.Recalculating) {
                    tts.speakText(context.getString(R.string.nav_recalc_failed_tts))
                }
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

        /** How far around the position to fetch cameras + speed limits. */
        const val HAZARD_QUERY_RADIUS_METERS = 2_000.0

        /** Distance after which the road context is re-queried. */
        const val HAZARD_REFRESH_DISTANCE_METERS = 250.0

        /** Time after which the road context is re-queried even without movement. */
        const val HAZARD_REFRESH_INTERVAL_MS = 30_000L

        /** A camera within this radius triggers the warning banner + TTS. */
        const val CAMERA_WARNING_RADIUS_METERS = 400.0

        /** Exit slack multiplier so GPS jitter at the boundary cannot flap. */
        const val CAMERA_EXIT_SLACK = 1.5

        /** Cameras count as "ahead" within +/- this bearing arc while moving. */
        const val CAMERA_AHEAD_ARC_DEGREES = 75.0

        /** Below this speed the bearing is treated as noise (any direction counts). */
        const val MOVING_SPEED_KMH = 8.0

        /** Fixes older than this flag the session as GPS-lost. */
        const val GPS_STALE_MS = 12_000L

        /** Watchdog cadence. */
        const val GPS_WATCHDOG_INTERVAL_MS = 3_000L

        /** Bounded re-subscribe attempts for the location flow. */
        const val MAX_GPS_RETRIES = 3

        /** Backoff base between location-flow retries. */
        const val GPS_RETRY_BASE_MS = 2_000L
    }
}
