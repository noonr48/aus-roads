/*
 * Pure, Room-free track statistics. Extracted so the distance / point-count computation can
 * be unit-tested on the JVM without an Android runtime (no Robolectric, no instrumented DB).
 *
 * Self-contained haversine: data:tracks deliberately does NOT depend on :core:geo in Wave A,
 * to avoid a cross-module coupling in the persistence layer. If a shared geodesy lib lands
 * later, this can delegate to it.
 */
package au.com.ausroads.data.tracks

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Aggregate stats computed over an ordered list of recorded points. */
internal data class TrackStatsResult(
    val distanceMeters: Double,
    val pointCount: Int,
)

internal object TrackStats {

    /** Mean Earth radius in metres (spherical approximation), per the haversine convention. */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /**
     * Great-circle distance between two lat/lon points in metres (haversine).
     * Inputs are decimal degrees; latitude then longitude.
     */
    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)

        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Total path distance (metres) summed over consecutive points, plus the point count.
     * Points are taken in the order given (the recorder's capture order); 0 or 1 points
     * yield distance 0.
     */
    fun compute(points: List<RecordedPoint>): TrackStatsResult {
        var distance = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            distance += haversineMeters(prev.latitude, prev.longitude, cur.latitude, cur.longitude)
        }
        return TrackStatsResult(distanceMeters = distance, pointCount = points.size)
    }
}
