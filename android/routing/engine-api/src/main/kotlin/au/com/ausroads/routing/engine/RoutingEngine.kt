/*
 * RoutingEngine — the abstraction every routing implementation satisfies.
 * v0.1 ships API only. v0.4 plugs in the Valhalla-backed implementation, which adds
 * closure-aware routing via `excludePolygons` and penalty routing via `closureFactor`.
 */
package au.com.ausroads.routing.engine

import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.core.model.RoutingEffect
import kotlinx.datetime.Instant

/** A request to compute a route. */
data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    val via: List<GeoPoint> = emptyList(),
    val costingProfile: CostingProfile = CostingProfile.AUTO,
    val avoidPolygons: List<List<GeoPoint>> = emptyList(),
    val penalties: List<RoutePenalty> = emptyList(),
    val departAt: Instant? = null,
    val options: RouteOptions = RouteOptions(),
)

/**
 * Route-avoidance preferences. The Valhalla backend maps each flag to a costing
 * option (`use_tolls` / `use_ferry` / `use_tracks`), nudging the router away from
 * the corresponding way type. "Unsealed" has no direct Valhalla boolean; it is
 * approximated by avoiding `tracks`.
 */
data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidUnsealed: Boolean = false,
    val avoidFerries: Boolean = false,
)

/** A penalty to apply to a specific point or along a polyline. */
data class RoutePenalty(
    val geometry: List<GeoPoint>,
    val effect: RoutingEffect,
)

/** Costing profiles. The Valhalla backend maps these to internal costings. */
enum class CostingProfile {
    AUTO,
    MOTORCYCLE,
    TRUCK,
    BICYCLE,
    PEDESTRIAN,
}

/** The result of a routing query. */
data class RouteResult(
    val distanceMeters: Int,
    val durationSeconds: Int,
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val warnings: List<String> = emptyList(),
)

data class Maneuver(
    val instruction: String,
    val lengthMeters: Int,
    val durationSeconds: Int,
    val beginShapeIndex: Int,
    val streetName: String?,
    val maneuverType: String,
    val lanes: List<LaneInfo>? = null,
)

data class LaneInfo(
    val isValid: Boolean,
    val indications: List<String>,
)

interface RoutingEngine {
    /**
     * Compute a route. The implementation is responsible for honoring [avoidPolygons]
     * and [penalties]; if it cannot (e.g. older Valhalla without `exclude_polygons`),
     * it must document the limitation and apply what it can.
     */
    suspend fun computeRoute(request: RouteRequest): RouteResult

    /** True if the engine is loaded and able to answer queries. */
    fun isReady(): Boolean
}
