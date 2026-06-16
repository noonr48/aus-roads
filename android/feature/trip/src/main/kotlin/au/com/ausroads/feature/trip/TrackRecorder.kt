/*
 * TrackRecorder — the pure, testable core of GPS track recording.
 *
 * It accumulates an ordered list of recorded GPS samples and answers questions about
 * them (point count, distance travelled, elapsed time) and can simplify the track with
 * the Douglas–Peucker line-generalisation algorithm. The live recording loop — location
 * callbacks, the foreground service, persistence via TrackRepository — is built on top of
 * this later; keeping the accumulator pure makes the geometry trivially unit-testable.
 *
 * Distance uses the shared spherical helper (MeasureGeometry.haversineMeters) so it agrees
 * with the rest of the app. Douglas–Peucker's point-to-segment distance uses a planar,
 * cos-latitude (equirectangular) approximation of perpendicular distance in metres — see
 * [perpendicularDistanceMeters] for the rationale and limits.
 */
package au.com.ausroads.feature.trip

import au.com.ausroads.core.geo.MeasureGeometry
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.data.tracks.RecordedPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Summary statistics for a recorded track.
 *
 * @property pointCount number of samples accumulated.
 * @property distanceMeters great-circle path length through the samples, in metres
 *   (0.0 for fewer than two points).
 * @property durationSeconds whole seconds between the first and last sample's [RecordedPoint.time]
 *   (0 for fewer than two points; never negative — samples are assumed time-ordered).
 */
data class RecordedStats(
    val pointCount: Int,
    val distanceMeters: Double,
    val durationSeconds: Long,
)

/**
 * Mutable accumulator of recorded GPS points. Not thread-safe; the recording loop is
 * expected to append from a single coroutine/handler. All geometry is computed on demand.
 */
class TrackRecorder {

    private val backing = mutableListOf<RecordedPoint>()

    /** The recorded samples, in append order. Read-only view (defensive copy on read is
     * unnecessary — the returned list is an unmodifiable wrapper over the live backing). */
    val points: List<RecordedPoint>
        get() = backing.toList()

    /** Append one sample to the end of the track. */
    fun append(point: RecordedPoint) {
        backing.add(point)
    }

    /** Drop all recorded samples, returning the recorder to its empty state. */
    fun clear() {
        backing.clear()
    }

    /**
     * Compute summary statistics over the currently-accumulated samples.
     *
     * Distance is the sum of haversine leg lengths; duration is `last.time - first.time`
     * in whole seconds. With 0 or 1 points, distance and duration are 0.
     */
    fun stats(): RecordedStats {
        val count = backing.size
        if (count < 2) {
            return RecordedStats(pointCount = count, distanceMeters = 0.0, durationSeconds = 0L)
        }
        val distance = MeasureGeometry.pathLengthMeters(backing.map { it.toGeoPoint() })
        val durationSeconds = backing.last().time.epochSeconds - backing.first().time.epochSeconds
        return RecordedStats(
            pointCount = count,
            distanceMeters = distance,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
        )
    }

    /**
     * Simplify the track with the Douglas–Peucker algorithm, dropping intermediate samples
     * that lie within [toleranceMeters] of the straight chord between retained samples.
     *
     * The first and last samples are always kept. A larger [toleranceMeters] removes more
     * points. Returns the input unchanged when there are fewer than three points (nothing to
     * drop) or when [toleranceMeters] is non-positive (no simplification requested).
     *
     * Perpendicular distance from a point to the chord is measured with the planar
     * cos-latitude approximation described on [perpendicularDistanceMeters], which is
     * accurate to well within the GPS noise floor over the short legs of a recorded track.
     */
    fun simplify(toleranceMeters: Double): List<RecordedPoint> {
        val n = backing.size
        if (n < 3 || toleranceMeters <= 0.0) return backing.toList()

        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        douglasPeucker(backing, 0, n - 1, toleranceMeters, keep)

        val result = ArrayList<RecordedPoint>(n)
        for (i in 0 until n) {
            if (keep[i]) result.add(backing[i])
        }
        return result
    }

    /**
     * Recursive Douglas–Peucker core. Over the inclusive index range [[startIndex],
     * [endIndex]], find the point farthest from the chord; if it exceeds [tolerance],
     * mark it kept and recurse on both halves. Iterative-via-recursion; depth is bounded
     * by the number of points, which for a single recorded trip is comfortably small.
     */
    private fun douglasPeucker(
        pts: List<RecordedPoint>,
        startIndex: Int,
        endIndex: Int,
        tolerance: Double,
        keep: BooleanArray,
    ) {
        if (endIndex <= startIndex + 1) return // no interior points between the endpoints

        val start = pts[startIndex]
        val end = pts[endIndex]
        var farthestIndex = -1
        var farthestDistance = 0.0
        for (i in (startIndex + 1) until endIndex) {
            val d = perpendicularDistanceMeters(pts[i], start, end)
            if (d > farthestDistance) {
                farthestDistance = d
                farthestIndex = i
            }
        }

        if (farthestDistance > tolerance && farthestIndex != -1) {
            keep[farthestIndex] = true
            douglasPeucker(pts, startIndex, farthestIndex, tolerance, keep)
            douglasPeucker(pts, farthestIndex, endIndex, tolerance, keep)
        }
        // else: every interior point is within tolerance of the chord ⇒ all dropped.
    }

    /**
     * Approximate perpendicular distance, in metres, from [point] to the segment
     * [[segStart], [segEnd]].
     *
     * APPROXIMATION: we project the three points onto a local equirectangular (plate
     * carrée) tangent plane centred on the segment's mean latitude:
     *   x = R · Δλ · cos(lat0),  y = R · Δφ
     * (λ longitude, φ latitude in radians, R = mean Earth radius, lat0 = mean latitude of
     * the segment endpoints), then compute the ordinary planar point-to-line-segment
     * distance. Over the short legs of a recorded GPS track (tens to a few hundred metres)
     * the cos-latitude scaling makes east–west and north–south metres commensurate, and the
     * error versus a rigorous cross-track (great-circle) distance is far below GPS noise.
     * It degrades only for very long legs or near the poles — neither applies to a recorded
     * road trip. This is the documented, intentionally-simpler stand-in for true cross-track
     * distance.
     *
     * If the segment endpoints coincide (degenerate chord), this falls back to the planar
     * distance from [point] to that single location.
     */
    private fun perpendicularDistanceMeters(
        point: RecordedPoint,
        segStart: RecordedPoint,
        segEnd: RecordedPoint,
    ): Double {
        val lat0Rad = Math.toRadians((segStart.latitude + segEnd.latitude) / 2.0)
        val cosLat0 = cos(lat0Rad)
        val r = MeasureGeometry.EARTH_RADIUS_METERS

        // Project to local-plane metres, using segStart as the local origin.
        fun px(p: RecordedPoint): Double = Math.toRadians(p.longitude - segStart.longitude) * cosLat0 * r
        fun py(p: RecordedPoint): Double = Math.toRadians(p.latitude - segStart.latitude) * r

        val ax = 0.0
        val ay = 0.0
        val bx = px(segEnd)
        val by = py(segEnd)
        val pxv = px(point)
        val pyv = py(point)

        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy
        if (segLenSq == 0.0) {
            // Degenerate segment: distance to the (coincident) endpoint.
            return sqrt(pxv * pxv + pyv * pyv)
        }

        // Projection factor t of P onto the segment, clamped to [0,1] so we measure
        // distance to the segment (not the infinite line) — matches DP's intent.
        val tRaw = (pxv * dx + pyv * dy) / segLenSq
        val t = tRaw.coerceIn(0.0, 1.0)
        val closestX = ax + t * dx
        val closestY = ay + t * dy
        val ex = pxv - closestX
        val ey = pyv - closestY
        return sqrt(ex * ex + ey * ey)
    }

    private fun RecordedPoint.toGeoPoint(): GeoPoint = GeoPoint(longitude = longitude, latitude = latitude)
}
