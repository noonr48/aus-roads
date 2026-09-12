package au.com.ausroads.feature.navigation

/**
 * Driver-assist road context at a position: the posted speed limit and the
 * fixed speed cameras nearby, sourced offline from the installed map pack.
 *
 * Pure Kotlin data — no platform or pack types — so the navigation module
 * stays decoupled from the search database layer; the app module provides
 * the concrete implementation (see SearchRoadHazardSource).
 */
data class RoadHazardCamera(
    /** Stable identity derived from the camera's coordinates. */
    val id: String,
    val latitude: Double,
    val longitude: Double,
    /** Enforced limit in km/h when known, else null. */
    val maxspeedKmh: Int?,
    /** Name of the enforced way when known, else null. */
    val wayName: String?,
    /** Great-circle distance from the query position, metres. */
    val distanceMeters: Double,
)

/**
 * Result of one [RoadHazardSource.contextAt] query.
 *
 * @property speedLimitKmh posted limit of the nearest indexed road, or null
 *   when the pack has no speed layer or nothing is in range.
 * @property cameras fixed cameras within the query radius, nearest-first.
 */
data class RoadHazardContext(
    val speedLimitKmh: Int?,
    val cameras: List<RoadHazardCamera>,
)

/**
 * Offline source of speed-limit and speed-camera context around a position.
 *
 * The navigation feature module cannot depend on the pack's search database
 * (that layer belongs to the app), so it consumes this port instead. The app
 * binds the real pack-backed implementation; tests bind fakes.
 */
interface RoadHazardSource {

    /**
     * Query road context around the given coordinate.
     *
     * @param radiusMeters how far around the position to look for cameras.
     *   Implementations widen the road-snapping window proportionally.
     */
    suspend fun contextAt(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
    ): RoadHazardContext
}
