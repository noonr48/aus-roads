package au.com.ausroads.feature.trip

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavioural tests for [SpeedLimitMonitor]: hysteresis (no flapping), the OVER latch,
 * null-limit handling, exact-boundary behaviour, and the optional APPROACHING margin.
 */
class SpeedLimitMonitorTest {

    // --- entering OVER -------------------------------------------------------

    @Test
    fun update_belowLimit_isNone() {
        val monitor = SpeedLimitMonitor()
        assertThat(monitor.update(speedKmh = 90.0, limitKmh = 100)).isEqualTo(SpeedAlert.NONE)
    }

    @Test
    fun update_withinBufferButNotPastIt_doesNotEnterOver() {
        // Default buffer 5 km/h: 100..105 is the band; must exceed 105 to engage.
        val monitor = SpeedLimitMonitor()
        assertThat(monitor.update(speedKmh = 103.0, limitKmh = 100)).isEqualTo(SpeedAlert.NONE)
        assertThat(monitor.update(speedKmh = 105.0, limitKmh = 100)).isEqualTo(SpeedAlert.NONE)
    }

    @Test
    fun update_pastBuffer_entersOver() {
        val monitor = SpeedLimitMonitor()
        assertThat(monitor.update(speedKmh = 106.0, limitKmh = 100)).isEqualTo(SpeedAlert.OVER)
        assertThat(monitor.current).isEqualTo(SpeedAlert.OVER)
    }

    @Test
    fun update_exactlyAtBufferTop_doesNotEnter_strictlyAboveRequired() {
        val monitor = SpeedLimitMonitor(overBufferKmh = 5.0)
        // limit+buffer == 105 exactly -> still NONE (entry is strictly greater).
        assertThat(monitor.update(speedKmh = 105.0, limitKmh = 100)).isEqualTo(SpeedAlert.NONE)
        // A hair over -> OVER.
        assertThat(monitor.update(speedKmh = 105.001, limitKmh = 100)).isEqualTo(SpeedAlert.OVER)
    }

    // --- hysteresis / latch (the key anti-flap property) ---------------------

    @Test
    fun update_jitterInBufferBand_staysOver_doesNotFlap() {
        val monitor = SpeedLimitMonitor(overBufferKmh = 5.0)
        // Engage OVER.
        assertThat(monitor.update(106.0, 100)).isEqualTo(SpeedAlert.OVER)
        // Now jitter around within (limit, limit+buffer]; OVER must hold every sample.
        val jitter = listOf(104.0, 101.0, 105.0, 100.5, 103.0, 102.0)
        for (speed in jitter) {
            assertThat(monitor.update(speed, 100)).isEqualTo(SpeedAlert.OVER)
        }
    }

    @Test
    fun update_staysOverUntilAtOrBelowLimit_thenClears() {
        val monitor = SpeedLimitMonitor()
        monitor.update(110.0, 100)
        assertThat(monitor.current).isEqualTo(SpeedAlert.OVER)
        // Just above the limit -> still OVER.
        assertThat(monitor.update(100.5, 100)).isEqualTo(SpeedAlert.OVER)
        // Exactly at the limit -> clears (hysteresis exit point is the limit itself).
        assertThat(monitor.update(100.0, 100)).isEqualTo(SpeedAlert.NONE)
    }

    @Test
    fun update_clearsBelowLimit() {
        val monitor = SpeedLimitMonitor()
        monitor.update(120.0, 100)
        assertThat(monitor.update(80.0, 100)).isEqualTo(SpeedAlert.NONE)
    }

    @Test
    fun update_reEntersOverAfterClearing_requiresFullBufferAgain() {
        val monitor = SpeedLimitMonitor(overBufferKmh = 5.0)
        monitor.update(110.0, 100) // OVER
        monitor.update(95.0, 100) // clears to NONE
        // Back into the band only -> must NOT re-engage without exceeding the buffer.
        assertThat(monitor.update(103.0, 100)).isEqualTo(SpeedAlert.NONE)
        assertThat(monitor.update(106.0, 100)).isEqualTo(SpeedAlert.OVER)
    }

    // --- null limit ----------------------------------------------------------

    @Test
    fun update_nullLimit_isNone() {
        val monitor = SpeedLimitMonitor()
        assertThat(monitor.update(150.0, null)).isEqualTo(SpeedAlert.NONE)
    }

    @Test
    fun update_nullLimit_resetsLatch() {
        val monitor = SpeedLimitMonitor()
        monitor.update(120.0, 100) // OVER
        assertThat(monitor.update(120.0, null)).isEqualTo(SpeedAlert.NONE)
        // After the null gap, the latch is cleared: the band alone must not be OVER.
        assertThat(monitor.update(103.0, 100)).isEqualTo(SpeedAlert.NONE)
    }

    // --- APPROACHING margin (opt-in) -----------------------------------------

    @Test
    fun update_approachingDisabledByDefault() {
        val monitor = SpeedLimitMonitor()
        // 99 of 100 with no margin configured -> plain NONE, never APPROACHING.
        assertThat(monitor.update(99.0, 100)).isEqualTo(SpeedAlert.NONE)
    }

    @Test
    fun update_withinApproachingMargin_isApproaching() {
        val monitor = SpeedLimitMonitor(approachingMarginKmh = 5.0)
        assertThat(monitor.update(96.0, 100)).isEqualTo(SpeedAlert.APPROACHING)
        // Exactly at the limit is still "within margin, not over" -> APPROACHING.
        assertThat(monitor.update(100.0, 100)).isEqualTo(SpeedAlert.APPROACHING)
        // Below the margin band -> NONE.
        assertThat(monitor.update(94.0, 100)).isEqualTo(SpeedAlert.NONE)
    }

    @Test
    fun update_approachingMargin_boundaryIsInclusive() {
        val monitor = SpeedLimitMonitor(approachingMarginKmh = 5.0)
        // limit-margin == 95 exactly -> inside the band.
        assertThat(monitor.update(95.0, 100)).isEqualTo(SpeedAlert.APPROACHING)
    }

    @Test
    fun update_clearingFromOverIntoApproachingBand_reportsApproaching() {
        val monitor = SpeedLimitMonitor(approachingMarginKmh = 5.0, overBufferKmh = 5.0)
        monitor.update(110.0, 100) // OVER
        // Drop to exactly the limit: latch clears, and 100 is inside the approaching band.
        assertThat(monitor.update(100.0, 100)).isEqualTo(SpeedAlert.APPROACHING)
    }

    // --- stateless classify --------------------------------------------------

    @Test
    fun classify_matchesLatchSemantics() {
        val monitor = SpeedLimitMonitor(overBufferKmh = 5.0)
        // From OVER, a speed in the band stays OVER.
        assertThat(monitor.classify(102.0, 100, SpeedAlert.OVER)).isEqualTo(SpeedAlert.OVER)
        // From NONE, the same speed does not engage.
        assertThat(monitor.classify(102.0, 100, SpeedAlert.NONE)).isEqualTo(SpeedAlert.NONE)
        // Null limit ignores prior state.
        assertThat(monitor.classify(200.0, null, SpeedAlert.OVER)).isEqualTo(SpeedAlert.NONE)
    }

    @Test
    fun classify_isPure_doesNotMutateMonitor() {
        val monitor = SpeedLimitMonitor()
        monitor.classify(200.0, 100, SpeedAlert.NONE)
        // current is driven only by update(), so it stays at its initial value.
        assertThat(monitor.current).isEqualTo(SpeedAlert.NONE)
    }

    // --- reset ---------------------------------------------------------------

    @Test
    fun reset_clearsLatch() {
        val monitor = SpeedLimitMonitor()
        monitor.update(130.0, 100) // OVER
        monitor.reset()
        assertThat(monitor.current).isEqualTo(SpeedAlert.NONE)
        assertThat(monitor.update(103.0, 100)).isEqualTo(SpeedAlert.NONE)
    }

    // --- constructor guards --------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejectsNegativeBuffer() {
        SpeedLimitMonitor(overBufferKmh = -1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejectsNegativeApproachingMargin() {
        SpeedLimitMonitor(approachingMarginKmh = -1.0)
    }
}
