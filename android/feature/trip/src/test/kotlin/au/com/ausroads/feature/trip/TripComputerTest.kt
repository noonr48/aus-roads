package au.com.ausroads.feature.trip

import au.com.ausroads.core.geo.SunCalc
import au.com.ausroads.data.tracks.RecordedPoint
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Test
import kotlin.time.Duration

/**
 * Tests for [TripComputer]: distance from haversine legs, moving/stopped time split
 * (both geometric and reported-speed paths), max speed, average moving speed, degenerate
 * empty/single-point inputs, reset, and the [SunCalc]-backed daylight delegation.
 */
class TripComputerTest {

    private val base: Instant = Instant.parse("2026-01-15T03:00:00Z")

    private fun point(
        lat: Double,
        lon: Double,
        atSeconds: Long,
        speedMps: Double? = null,
    ): RecordedPoint = RecordedPoint(
        latitude = lat,
        longitude = lon,
        time = base.plusSeconds(atSeconds),
        speedMps = speedMps,
    )

    private fun Instant.plusSeconds(s: Long): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() + s * 1000L)

    // --- degenerate inputs ---------------------------------------------------

    @Test
    fun snapshot_noPoints_isAllZero() {
        val stats = TripComputer().snapshot()
        assertThat(stats.distanceMeters).isEqualTo(0.0)
        assertThat(stats.movingTimeSeconds).isEqualTo(0L)
        assertThat(stats.stoppedTimeSeconds).isEqualTo(0L)
        assertThat(stats.averageMovingSpeedKmh).isEqualTo(0.0)
        assertThat(stats.maxSpeedKmh).isEqualTo(0.0)
    }

    @Test
    fun snapshot_singlePoint_hasNoDistanceOrTime_butKeepsMaxSpeed() {
        val computer = TripComputer()
        computer.onPoint(point(-34.9, 138.6, atSeconds = 0, speedMps = 12.0))
        val stats = computer.snapshot()
        assertThat(stats.distanceMeters).isEqualTo(0.0)
        assertThat(stats.movingTimeSeconds).isEqualTo(0L)
        assertThat(stats.stoppedTimeSeconds).isEqualTo(0L)
        assertThat(stats.averageMovingSpeedKmh).isEqualTo(0.0)
        // 12 m/s == 43.2 km/h.
        assertThat(stats.maxSpeedKmh).isWithin(1e-6).of(43.2)
    }

    // --- distance ------------------------------------------------------------

    @Test
    fun distance_sumsConsecutiveHaversineLegs() {
        // Two ~91 m east steps + one zero-move leg => ~273.6 m total (see fixture).
        val computer = geometricFixture()
        // Wide tolerance: spherical model, asserting the right order of magnitude/sum.
        assertThat(computer.snapshot().distanceMeters).isWithin(5.0).of(273.59)
    }

    @Test
    fun distance_isMonotonicAndPositiveForAMovingTrack() {
        val computer = TripComputer()
        computer.onPoint(point(-34.9, 138.6000, 0))
        computer.onPoint(point(-34.9, 138.6010, 10))
        val afterFirstLeg = computer.snapshot().distanceMeters
        computer.onPoint(point(-34.9, 138.6030, 20))
        val afterSecondLeg = computer.snapshot().distanceMeters
        assertThat(afterFirstLeg).isGreaterThan(0.0)
        assertThat(afterSecondLeg).isGreaterThan(afterFirstLeg)
    }

    // --- moving / stopped split (geometric, no reported speeds) --------------

    @Test
    fun split_geometric_partitionsTimeByDerivedSpeed() {
        // leg0 10s @ ~33 km/h (moving), leg1 20s @ 0 km/h (stopped), leg2 10s @ ~66 (moving).
        val stats = geometricFixture().snapshot()
        assertThat(stats.movingTimeSeconds).isEqualTo(20L)
        assertThat(stats.stoppedTimeSeconds).isEqualTo(20L)
    }

    @Test
    fun split_geometric_maxSpeedIsZeroWithoutReportedSpeeds() {
        // Max speed comes only from points' reported speedMps, never from leg geometry.
        assertThat(geometricFixture().snapshot().maxSpeedKmh).isEqualTo(0.0)
    }

    @Test
    fun averageMovingSpeed_isDistanceOverMovingTime() {
        // 273.59 m over 20 s moving => 13.6795 m/s => ~49.25 km/h.
        val stats = geometricFixture().snapshot()
        assertThat(stats.averageMovingSpeedKmh).isWithin(1.0).of(49.25)
    }

    // --- moving / stopped split (reported speeds + max speed) ----------------

    @Test
    fun split_reportedSpeeds_useEndpointAverageAndTrackMaxSpeed() {
        // Vehicle moves, then sits still for one 20 s leg (both endpoints 0 m/s), then moves.
        val computer = TripComputer()
        computer.onPoint(point(-34.9, 138.6000, 0, speedMps = 10.0))
        computer.onPoint(point(-34.9, 138.6010, 10, speedMps = 14.0))
        computer.onPoint(point(-34.9, 138.6020, 20, speedMps = 0.0))
        computer.onPoint(point(-34.9, 138.6020, 40, speedMps = 0.0))
        computer.onPoint(point(-34.9, 138.6040, 50, speedMps = 20.0))

        val stats = computer.snapshot()
        assertThat(stats.movingTimeSeconds).isEqualTo(30L)
        assertThat(stats.stoppedTimeSeconds).isEqualTo(20L)
        assertThat(stats.distanceMeters).isWithin(5.0).of(364.79)
        // Fastest reported sample: 20 m/s == 72 km/h.
        assertThat(stats.maxSpeedKmh).isWithin(1e-6).of(72.0)
        // 364.79 m over 30 s moving => ~43.77 km/h.
        assertThat(stats.averageMovingSpeedKmh).isWithin(1.0).of(43.77)
    }

    @Test
    fun split_slowCreepBelowThreshold_countsAsStopped() {
        // 1 m/s == 3.6 km/h > default 3.0, so pick 0.5 m/s == 1.8 km/h (stopped).
        val computer = TripComputer(movingThresholdKmh = 3.0)
        computer.onPoint(point(-34.9, 138.6000, 0, speedMps = 0.5))
        computer.onPoint(point(-34.9, 138.60005, 10, speedMps = 0.5))
        val stats = computer.snapshot()
        assertThat(stats.movingTimeSeconds).isEqualTo(0L)
        assertThat(stats.stoppedTimeSeconds).isEqualTo(10L)
    }

    @Test
    fun split_customThreshold_movesBoundary() {
        // Endpoints avg 4 m/s == 14.4 km/h: moving under default, stopped under a 20 km/h bar.
        fun build(threshold: Double): TripStats {
            val c = TripComputer(movingThresholdKmh = threshold)
            c.onPoint(point(-34.9, 138.6000, 0, speedMps = 4.0))
            c.onPoint(point(-34.9, 138.6010, 10, speedMps = 4.0))
            return c.snapshot()
        }
        assertThat(build(3.0).movingTimeSeconds).isEqualTo(10L)
        assertThat(build(20.0).stoppedTimeSeconds).isEqualTo(10L)
    }

    // --- reset ---------------------------------------------------------------

    @Test
    fun reset_clearsAllAccumulatedState() {
        val computer = geometricFixture()
        assertThat(computer.snapshot().distanceMeters).isGreaterThan(0.0)
        computer.reset()
        val stats = computer.snapshot()
        assertThat(stats.distanceMeters).isEqualTo(0.0)
        assertThat(stats.movingTimeSeconds).isEqualTo(0L)
        assertThat(stats.stoppedTimeSeconds).isEqualTo(0L)
        assertThat(stats.maxSpeedKmh).isEqualTo(0.0)
    }

    // --- daylight delegation -------------------------------------------------

    @Test
    fun daylightRemaining_matchesSunCalcForTheUtcDay_andIsNonNegative() {
        // Noon UTC over London in mid-June: longitude ~0 so the UTC day == the local day,
        // and a summer midday is squarely between sunrise (~03:42) and sunset (~20:19).
        val now = Instant.parse("2026-06-15T12:00:00Z")
        val lat = 51.5
        val lon = -0.1
        val expected = SunCalc.daylightRemaining(
            now = now,
            latitude = lat,
            longitude = lon,
            date = now.toLocalDateTime(TimeZone.UTC).date,
        )
        val actual = TripComputer().daylightRemaining(now, lat, lon)
        // Delegation must reproduce SunCalc exactly for the derived UTC date.
        assertThat(actual).isEqualTo(expected)
        assertThat(actual >= Duration.ZERO).isTrue()
        // Sanity: a summer midday still has daylight left.
        assertThat(actual > Duration.ZERO).isTrue()
    }

    @Test
    fun daylightRemaining_isNeverNegative_afterSunset() {
        // Well after sunset over London (~22:30 UTC, sunset ~20:19): SunCalc clamps to zero.
        val night = Instant.parse("2026-06-15T22:30:00Z")
        val remaining = TripComputer().daylightRemaining(night, 51.5, -0.1)
        assertThat(remaining).isEqualTo(Duration.ZERO)
    }

    // --- fixtures ------------------------------------------------------------

    /**
     * Three legs, no reported speeds: ~91 m east (10 s), stationary (20 s), ~182 m east
     * (10 s). Distance ~273.6 m; moving 20 s; stopped 20 s.
     */
    private fun geometricFixture(): TripComputer {
        val computer = TripComputer()
        computer.onPoint(point(-34.9, 138.6000, 0))
        computer.onPoint(point(-34.9, 138.6010, 10))
        computer.onPoint(point(-34.9, 138.6010, 30))
        computer.onPoint(point(-34.9, 138.6030, 40))
        return computer
    }
}
