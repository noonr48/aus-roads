package au.com.ausroads.feature.navigation

import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.Maneuver

sealed interface NavigationState {
    data object Idle : NavigationState

    /**
     * Navigation entry refused: ACCESS_FINE_LOCATION is unavailable (the privacy-first
     * offline flavor strips it entirely), so genuine position tracking is impossible.
     * Per docs/notes/privacy.md, navigation MODE is unavailable without the permission —
     * the app must never fabricate progress toward arrival.
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
    ) : NavigationState
    data class Recalculating(
        val previousState: Navigating,
        val destination: GeoPoint,
    ) : NavigationState
    data object Arrived : NavigationState
}
