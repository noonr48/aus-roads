package au.com.ausroads.feature.navigation

import kotlinx.coroutines.flow.Flow

/**
 * A single position sample for turn-by-turn navigation. Plain data — no platform
 * or Google Play Services types — so it (and the [NavigationLocationSource]
 * contract) stay in this gms-free module.
 */
data class NavigationLocation(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val bearing: Float,
    val accuracyMeters: Float,
    val timestamp: Long,
)

/**
 * Live-position source for navigation. The real fused-location implementation is
 * provided per app flavor: the withNetwork flavor binds a Google Play Services
 * backed source, while the privacy-first offline flavor binds a no-op (it has no
 * location permission and links no Play Services, so navigation falls back to
 * route-simulated tracking there). Keeping this an interface holds Google Play
 * Services out of the navigation module and out of the offline build entirely.
 */
interface NavigationLocationSource {
    fun locationUpdates(intervalMs: Long = 1000): Flow<NavigationLocation>
}
