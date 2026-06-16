package au.com.ausroads.feature.navigation

import au.com.ausroads.core.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, Android-free navigation geometry. Extracted from [NavigationViewModel] so the
 * off-route / arrival / ETA math has a single tested source of truth (the ViewModel
 * delegates to it). No coroutines, no Context — unit-testable directly on the JVM.
 */
internal object NavigationGeometry {

    data class NearestRoutePoint(val index: Int, val distanceMeters: Double)

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance between two points, in metres. */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Nearest route vertex to [pos], or null for an empty route. */
    fun nearestRoutePoint(geometry: List<GeoPoint>, pos: GeoPoint): NearestRoutePoint? {
        if (geometry.isEmpty()) return null
        var minDist = Double.MAX_VALUE
        var nearestIdx = 0
        for (i in geometry.indices) {
            val d = haversineMeters(pos, geometry[i])
            if (d < minDist) {
                minDist = d
                nearestIdx = i
            }
        }
        return NearestRoutePoint(nearestIdx, minDist)
    }

    /** Metres remaining along [geometry] from the vertex nearest [pos] through to the end. */
    fun remainingDistanceMeters(geometry: List<GeoPoint>, pos: GeoPoint): Double {
        val nearest = nearestRoutePoint(geometry, pos) ?: return 0.0
        return distanceFromIndex(geometry, nearest.index) + nearest.distanceMeters
    }

    /** Metres along [geometry] from the vertex at [fromIndex] through to the end. */
    fun distanceFromIndex(geometry: List<GeoPoint>, fromIndex: Int): Double {
        var total = 0.0
        for (i in (fromIndex + 1) until geometry.size) {
            total += haversineMeters(geometry[i - 1], geometry[i])
        }
        return total
    }
}
