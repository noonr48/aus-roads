package au.com.ausroads.feature.trip

import au.com.ausroads.data.tracks.RecordedPoint
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import org.junit.Test

class TrackRecorderTest {

    private val t0 = Instant.parse("2026-06-13T00:00:00Z")

    private fun pt(lat: Double, lon: Double, atSeconds: Long): RecordedPoint =
        RecordedPoint(latitude = lat, longitude = lon, time = t0 + atSeconds.seconds)

    // --- accumulation + stats -------------------------------------------------

    @Test
    fun emptyRecorder_hasZeroStats() {
        val rec = TrackRecorder()
        val s = rec.stats()
        assertThat(s.pointCount).isEqualTo(0)
        assertThat(s.distanceMeters).isEqualTo(0.0)
        assertThat(s.durationSeconds).isEqualTo(0L)
    }

    @Test
    fun singlePoint_zeroDistanceAndDuration() {
        val rec = TrackRecorder()
        rec.append(pt(0.0, 0.0, 0))
        val s = rec.stats()
        assertThat(s.pointCount).isEqualTo(1)
        assertThat(s.distanceMeters).isEqualTo(0.0)
        assertThat(s.durationSeconds).isEqualTo(0L)
    }

    @Test
    fun appendUpdatesPointsAndStats() {
        val rec = TrackRecorder()
        // Two points one degree of longitude apart on the equator. Haversine distance is
        // R * Δλ(rad) = 6_371_000 * (1° in rad) ≈ 111_194.9 m.
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.0, 1.0, 120))

        assertThat(rec.points).hasSize(2)
        val s = rec.stats()
        assertThat(s.pointCount).isEqualTo(2)
        assertThat(s.durationSeconds).isEqualTo(120L)
        // Expected great-circle distance to within a metre.
        val expected = 6_371_000.0 * Math.toRadians(1.0)
        assertThat(s.distanceMeters).isWithin(1.0).of(expected)
    }

    @Test
    fun distanceIsSumOfLegs() {
        val rec = TrackRecorder()
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.0, 1.0, 60))
        rec.append(pt(0.0, 2.0, 120))
        val s = rec.stats()
        val oneDeg = 6_371_000.0 * Math.toRadians(1.0)
        assertThat(s.distanceMeters).isWithin(2.0).of(oneDeg * 2.0)
        assertThat(s.durationSeconds).isEqualTo(120L)
    }

    @Test
    fun clear_resetsToEmpty() {
        val rec = TrackRecorder()
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.0, 1.0, 60))
        rec.clear()
        assertThat(rec.points).isEmpty()
        assertThat(rec.stats().pointCount).isEqualTo(0)
        assertThat(rec.stats().distanceMeters).isEqualTo(0.0)
    }

    @Test
    fun points_isOrderedByAppend() {
        val rec = TrackRecorder()
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.0, 0.5, 30))
        rec.append(pt(0.0, 1.0, 60))
        assertThat(rec.points.map { it.longitude }).containsExactly(0.0, 0.5, 1.0).inOrder()
    }

    // --- simplify (Douglas–Peucker) ------------------------------------------

    @Test
    fun simplify_empty_returnsEmpty() {
        val rec = TrackRecorder()
        assertThat(rec.simplify(10.0)).isEmpty()
    }

    @Test
    fun simplify_singlePoint_returnsThatPoint() {
        val rec = TrackRecorder()
        rec.append(pt(0.0, 0.0, 0))
        assertThat(rec.simplify(10.0)).hasSize(1)
    }

    @Test
    fun simplify_twoPoints_returnsBoth() {
        val rec = TrackRecorder()
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.0, 1.0, 60))
        val out = rec.simplify(10.0)
        assertThat(out).hasSize(2)
        assertThat(out.first().longitude).isEqualTo(0.0)
        assertThat(out.last().longitude).isEqualTo(1.0)
    }

    @Test
    fun simplify_keepsEndpoints() {
        val rec = TrackRecorder()
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.0005, 0.5, 30)) // off the chord by ~55 m
        rec.append(pt(0.0, 1.0, 60))
        // Even with a tiny tolerance, endpoints are always present.
        val out = rec.simplify(1.0)
        assertThat(out.first().longitude).isEqualTo(0.0)
        assertThat(out.last().longitude).isEqualTo(1.0)
    }

    @Test
    fun simplify_removesCollinearInteriorPoint() {
        val rec = TrackRecorder()
        // Perfectly collinear along the equator: the middle point lies exactly on the chord,
        // so its perpendicular distance is ~0 and it is dropped at a small tolerance.
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.0, 0.5, 30))
        rec.append(pt(0.0, 1.0, 60))
        val out = rec.simplify(5.0)
        assertThat(out).hasSize(2)
        assertThat(out.map { it.longitude }).containsExactly(0.0, 1.0).inOrder()
    }

    @Test
    fun simplify_keepsPointThatExceedsTolerance() {
        val rec = TrackRecorder()
        // Middle point offset north by 0.001° ≈ 111 m from the east-west chord.
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.001, 0.5, 30))
        rec.append(pt(0.0, 1.0, 60))
        // Tolerance 50 m < ~111 m offset, so the bulge point must be retained.
        val out = rec.simplify(50.0)
        assertThat(out).hasSize(3)
    }

    @Test
    fun simplify_higherToleranceRemovesMore() {
        val rec = TrackRecorder()
        // A gentle zig-zag: each interior point offset ~111 m (0.001°) from the global chord.
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.001, 0.25, 15))
        rec.append(pt(-0.001, 0.5, 30))
        rec.append(pt(0.001, 0.75, 45))
        rec.append(pt(0.0, 1.0, 60))

        val tight = rec.simplify(50.0) // < 111 m: keeps the bulges.
        val loose = rec.simplify(300.0) // > 111 m: collapses to the endpoints.

        assertThat(tight.size).isGreaterThan(loose.size)
        // Loose tolerance swallows every interior point.
        assertThat(loose).hasSize(2)
        assertThat(loose.map { it.longitude }).containsExactly(0.0, 1.0).inOrder()
        // Tight tolerance keeps all five (each bulge exceeds 50 m).
        assertThat(tight).hasSize(5)
    }

    @Test
    fun simplify_nonPositiveTolerance_returnsAllPoints() {
        val rec = TrackRecorder()
        rec.append(pt(0.0, 0.0, 0))
        rec.append(pt(0.0, 0.5, 30))
        rec.append(pt(0.0, 1.0, 60))
        // Zero / negative tolerance means "no simplification".
        assertThat(rec.simplify(0.0)).hasSize(3)
        assertThat(rec.simplify(-5.0)).hasSize(3)
    }
}
