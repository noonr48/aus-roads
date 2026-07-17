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
 * backed source, while the privacy-first offline flavor binds Android's native
 * on-device location provider. Keeping this interface holds Google Play Services
 * out of the offline build entirely.
 */
interface NavigationLocationSource {
    fun locationUpdates(intervalMs: Long = 1000): Flow<NavigationLocation>
}

class NavigationLocationUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
