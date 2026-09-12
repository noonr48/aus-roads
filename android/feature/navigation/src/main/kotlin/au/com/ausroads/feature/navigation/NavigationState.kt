package au.com.ausroads.feature.navigation

import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.Maneuver

/** A fixed speed camera currently within the warning radius while navigating. */
data class CameraWarning(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    /** Enforced limit in km/h when known, else null. */
    val maxspeedKmh: Int?,
    /** Name of the enforced way when known, else null. */
    val wayName: String?,
    /** Live great-circle distance from the current position, metres. */
    val distanceMeters: Double,
)

sealed interface NavigationState {
    data object Idle : NavigationState

    /**
     * Navigation entry refused: ACCESS_FINE_LOCATION is unavailable (the privacy-first
     * offline flavor strips it entirely), so genuine position tracking is impossible.
     * Per docs/notes/privacy.md, navigation MODE is unavailable without the permission —
     * the app must never fabricate progress toward arrival. Dismissible via
     * [NavigationViewModel.dismissLocationUnavailable].
     */
    data object LocationUnavailable : NavigationState
    data class Navigating(
        val currentManeuver: Maneuver?,
        val nextManeuver: Maneuver?,
        val remainingDistanceMeters: Double,
        val remainingDurationSeconds: Double,
        val currentSpeedKmh: Double = 0.0,
        val speedLimitKmh: Int? = null,
        val isOverspeeding: Boolean = false,
        val maneuverIndex: Int = 0,
        val totalManeuvers: Int = 0,
        /** Live distance to the point where the next maneuver begins, metres. */
        val nextManeuverDistanceMeters: Double? = null,
        /** True from session start until the first real position fix arrives. */
        val awaitingGpsFix: Boolean = true,
        /** True when fixes have gone stale (no sample within the staleness window). */
        val gpsLost: Boolean = false,
        /** Nearest warned speed camera, or null when none is in range. */
        val cameraWarning: CameraWarning? = null,
    ) : NavigationState
    data class Recalculating(
        val previousState: Navigating,
        val destination: GeoPoint,
    ) : NavigationState
    data object Arrived : NavigationState
}
