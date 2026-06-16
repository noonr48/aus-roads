/*
 * Spherical measurement helpers: great-circle path length and spherical-excess
 * polygon area on an Earth-sized sphere. Pure math, no projection libraries.
 */
package au.com.ausroads.core.geo

import au.com.ausroads.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance and area on a sphere of radius [EARTH_RADIUS_METERS].
 *
 * These are spherical approximations (not the WGS84 ellipsoid); they are accurate
 * to well within a percent for the road-trip distances this app deals with.
 */
object MeasureGeometry {

    /** Mean Earth radius in metres (spherical approximation). */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /**
     * Total great-circle length, in metres, of the polyline through [points]
     * (sum of the haversine distance of each consecutive leg).
     *
     * Returns 0.0 for fewer than two points.
     */
    fun pathLengthMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += haversineMeters(points[i], points[i + 1])
        }
        return total
    }

    /**
     * Great-circle distance in metres between two points using the haversine formula.
     */
    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinDLat = sin(dLat / 2.0)
        val sinDLon = sin(dLon / 2.0)
        val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        val c = 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Area in square metres enclosed by the polygon [ring] on the sphere, computed via
     * the spherical-excess (L'Huilier-style shoelace) formula. The ring need not repeat
     * its first point; it is treated as implicitly closed.
     *
     * Returns 0.0 for fewer than three points. The result is always non-negative
     * (orientation-independent).
     *
     * Reference: spherical polygon area, Bevis & Cambareri (1987); equivalent to the
     * formula used by PostGIS/Google's `computeSignedArea`.
     */
    fun polygonAreaSquareMeters(ring: List<GeoPoint>): Double {
        val n = ring.size
        if (n < 3) return 0.0
        var total = 0.0
        for (i in 0 until n) {
            val p1 = ring[i]
            val p2 = ring[(i + 1) % n]
            val lon1 = Math.toRadians(p1.longitude)
            val lon2 = Math.toRadians(p2.longitude)
            val lat1 = Math.toRadians(p1.latitude)
            val lat2 = Math.toRadians(p2.latitude)
            total += (lon2 - lon1) * (2.0 + sin(lat1) + sin(lat2))
        }
        val area = total * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS / 2.0
        return abs(area)
    }
}
