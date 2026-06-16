package au.com.ausroads.data.tracks

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlin.math.abs
import org.junit.Test

/**
 * Pure-JVM tests for the Room-free stats helper. Verifies the haversine distance and the
 * RecordedPoint -> (distanceMeters, pointCount) mapping the repository uses on save.
 */
class TrackStatsTest {

    private fun pt(lat: Double, lon: Double): RecordedPoint =
        RecordedPoint(latitude = lat, longitude = lon, time = Instant.fromEpochMilliseconds(0L))

    // --- haversineMeters ------------------------------------------------------

    @Test
    fun `haversine of a point to itself is zero`() {
        assertThat(TrackStats.haversineMeters(-33.8688, 151.2093, -33.8688, 151.2093))
            .isEqualTo(0.0)
    }

    @Test
    fun `haversine of one degree of latitude is about 111 point 2 km`() {
        // One degree of latitude along a meridian = R * (pi/180).
        val expected = TrackStats.EARTH_RADIUS_METERS * Math.toRadians(1.0) // ~111194.9 m
        val actual = TrackStats.haversineMeters(0.0, 0.0, 1.0, 0.0)

        assertThat(abs(actual - expected)).isLessThan(0.5)
        // Sanity bound independent of the formula restatement above.
        assertThat(actual).isWithin(200.0).of(111_195.0)
    }

    @Test
    fun `haversine is symmetric`() {
        val ab = TrackStats.haversineMeters(-33.8688, 151.2093, -37.8136, 144.9631)
        val ba = TrackStats.haversineMeters(-37.8136, 144.9631, -33.8688, 151.2093)

        assertThat(abs(ab - ba)).isLessThan(1e-6)
    }

    @Test
    fun `haversine Sydney to Melbourne is roughly 714 km`() {
        // Sydney (-33.8688, 151.2093) to Melbourne (-37.8136, 144.9631): ~713-714 km great-circle.
        val meters = TrackStats.haversineMeters(-33.8688, 151.2093, -37.8136, 144.9631)

        assertThat(meters).isWithin(5_000.0).of(714_000.0)
    }

    // --- compute (RecordedPoint -> stats) ------------------------------------

    @Test
    fun `compute on empty list is zero distance and zero count`() {
        val stats = TrackStats.compute(emptyList())

        assertThat(stats.distanceMeters).isEqualTo(0.0)
        assertThat(stats.pointCount).isEqualTo(0)
    }

    @Test
    fun `compute on a single point is zero distance and count one`() {
        val stats = TrackStats.compute(listOf(pt(-33.8688, 151.2093)))

        assertThat(stats.distanceMeters).isEqualTo(0.0)
        assertThat(stats.pointCount).isEqualTo(1)
    }

    @Test
    fun `compute on two points equals the haversine between them`() {
        val a = pt(0.0, 0.0)
        val b = pt(0.0, 1.0) // one degree of longitude at the equator
        val stats = TrackStats.compute(listOf(a, b))

        val expected = TrackStats.haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertThat(stats.pointCount).isEqualTo(2)
        assertThat(abs(stats.distanceMeters - expected)).isLessThan(1e-6)
    }

    @Test
    fun `compute sums consecutive segments`() {
        // Three points forming two legs; total must equal leg1 + leg2.
        val a = pt(0.0, 0.0)
        val b = pt(0.0, 1.0)
        val c = pt(1.0, 1.0)
        val stats = TrackStats.compute(listOf(a, b, c))

        val leg1 = TrackStats.haversineMeters(0.0, 0.0, 0.0, 1.0)
        val leg2 = TrackStats.haversineMeters(0.0, 1.0, 1.0, 1.0)

        assertThat(stats.pointCount).isEqualTo(3)
        assertThat(abs(stats.distanceMeters - (leg1 + leg2))).isLessThan(1e-6)
    }

    @Test
    fun `compute counts every point even when stationary`() {
        // All identical points: distance stays 0 but the count reflects samples taken.
        val stationary = List(5) { pt(-37.8136, 144.9631) }
        val stats = TrackStats.compute(stationary)

        assertThat(stats.distanceMeters).isEqualTo(0.0)
        assertThat(stats.pointCount).isEqualTo(5)
    }
}
