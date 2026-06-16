/*
 * Stateful trip-statistics accumulator.
 *
 * Points are fed one at a time as they are recorded. Distance is the sum of
 * consecutive great-circle (haversine) legs; elapsed time between consecutive points
 * is split into "moving" and "stopped" buckets by a speed threshold, using each
 * point's reported speed when available and otherwise the leg's average speed
 * (distance / elapsed). Average moving speed is total distance over moving time.
 */
package au.com.ausroads.feature.trip

import au.com.ausroads.core.geo.MeasureGeometry
import au.com.ausroads.core.geo.SunCalc
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.data.tracks.RecordedPoint
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

/**
 * Aggregate statistics for a trip in progress.
 *
 * @property distanceMeters total great-circle distance travelled, in metres.
 * @property movingTimeSeconds seconds spent above the moving-speed threshold.
 * @property stoppedTimeSeconds seconds spent at/below the moving-speed threshold.
 * @property averageMovingSpeedKmh distance divided by moving time, in km/h
 *   (`0.0` when there is no moving time yet).
 * @property maxSpeedKmh the fastest point speed seen, in km/h.
 */
data class TripStats(
    val distanceMeters: Double,
    val movingTimeSeconds: Long,
    val stoppedTimeSeconds: Long,
    val averageMovingSpeedKmh: Double,
    val maxSpeedKmh: Double,
)

/**
 * Accumulates [TripStats] from a stream of [RecordedPoint]s.
 *
 * Feed points in time order via [onPoint]; read the running totals with [snapshot];
 * clear with [reset]. Not thread-safe; drive from a single coroutine/looper.
 *
 * @param movingThresholdKmh speeds at or below this (km/h) count the leg's elapsed
 *   time as "stopped"; above it as "moving". Default `3.0`.
 */
class TripComputer(
    private val movingThresholdKmh: Double = 3.0,
) {
    init {
        require(movingThresholdKmh >= 0.0) {
            "movingThresholdKmh must be >= 0: $movingThresholdKmh"
        }
    }

    private var previous: RecordedPoint? = null
    private var distanceMeters: Double = 0.0
    private var movingSeconds: Double = 0.0
    private var stoppedSeconds: Double = 0.0
    private var maxSpeedKmh: Double = 0.0

    /**
     * Add the next recorded [point]. The first point only seeds the max-speed value
     * and the leg origin; distance/time accrue from the second point onward.
     */
    fun onPoint(point: RecordedPoint) {
        accountPointSpeed(point)

        val prev = previous
        if (prev == null) {
            previous = point
            return
        }

        val legMeters = MeasureGeometry.haversineMeters(prev.toGeoPoint(), point.toGeoPoint())
        distanceMeters += legMeters

        val elapsedSeconds = (point.time - prev.time).inWholeMilliseconds / MILLIS_PER_SECOND
        if (elapsedSeconds > 0.0) {
            val legSpeedKmh = legClassificationSpeedKmh(prev, point, legMeters, elapsedSeconds)
            if (legSpeedKmh > movingThresholdKmh) {
                movingSeconds += elapsedSeconds
            } else {
                stoppedSeconds += elapsedSeconds
            }
        }

        previous = point
    }

    /** Current running totals. Returns all-zero stats before any point is fed. */
    fun snapshot(): TripStats {
        val movingWhole = movingSeconds.toLong()
        val averageMovingKmh = if (movingSeconds > 0.0) {
            metersPerSecondToKmh(distanceMeters / movingSeconds)
        } else {
            0.0
        }
        return TripStats(
            distanceMeters = distanceMeters,
            movingTimeSeconds = movingWhole,
            stoppedTimeSeconds = stoppedSeconds.toLong(),
            averageMovingSpeedKmh = averageMovingKmh,
            maxSpeedKmh = maxSpeedKmh,
        )
    }

    /** Clear all accumulated state back to a fresh trip. */
    fun reset() {
        previous = null
        distanceMeters = 0.0
        movingSeconds = 0.0
        stoppedSeconds = 0.0
        maxSpeedKmh = 0.0
    }

    /**
     * Daylight remaining at [now] for the location [latitude]/[longitude], delegating to
     * [SunCalc]. The calendar day is taken from [now] in UTC (matching SunCalc's UTC
     * clock). Never negative; [Duration.ZERO] outside daylight or in polar day/night.
     */
    fun daylightRemaining(now: Instant, latitude: Double, longitude: Double): Duration {
        val date = now.toLocalDateTime(TimeZone.UTC).date
        return SunCalc.daylightRemaining(
            now = now,
            latitude = latitude,
            longitude = longitude,
            date = date,
        )
    }

    /** Fold a point's reported speed (if any) into the running max. */
    private fun accountPointSpeed(point: RecordedPoint) {
        val speedMps = point.speedMps ?: return
        val speedKmh = metersPerSecondToKmh(speedMps)
        if (speedKmh > maxSpeedKmh) {
            maxSpeedKmh = speedKmh
        }
    }

    /**
     * The speed (km/h) used to classify a leg as moving vs stopped: the average of the
     * two endpoints' reported speeds when available, otherwise the leg's geometric
     * average (distance / elapsed time).
     */
    private fun legClassificationSpeedKmh(
        prev: RecordedPoint,
        point: RecordedPoint,
        legMeters: Double,
        elapsedSeconds: Double,
    ): Double {
        val reported = averageReportedSpeedMps(prev, point)
        val speedMps = reported ?: (legMeters / elapsedSeconds)
        return metersPerSecondToKmh(speedMps)
    }

    /**
     * Mean of the endpoints' reported `speedMps`, or `null` when neither point reports
     * one (so the caller falls back to the geometric leg speed).
     */
    private fun averageReportedSpeedMps(prev: RecordedPoint, point: RecordedPoint): Double? {
        val a = prev.speedMps
        val b = point.speedMps
        return when {
            a != null && b != null -> (a + b) / 2.0
            a != null -> a
            b != null -> b
            else -> null
        }
    }

    private fun RecordedPoint.toGeoPoint(): GeoPoint =
        GeoPoint(longitude = longitude, latitude = latitude)

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
        const val SECONDS_PER_HOUR = 3600.0
        const val METERS_PER_KM = 1000.0

        /** Convert metres-per-second to kilometres-per-hour. */
        fun metersPerSecondToKmh(mps: Double): Double = mps * SECONDS_PER_HOUR / METERS_PER_KM
    }
}
