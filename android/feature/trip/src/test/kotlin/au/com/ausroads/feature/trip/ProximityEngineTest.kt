package au.com.ausroads.feature.trip

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [ProximityEngine]: Enter on crossing in, no duplicate Enter while inside,
 * Exit only beyond `radius * exitSlack`, no Exit inside the hysteresis band, and
 * independence across multiple targets.
 *
 * Geometry fixture: target centre (-34.9000, 138.6000), radius 100 m, default slack 1.2
 * (=> exit threshold 120 m). Test points are placed due north of the centre at known
 * distances: ~50 m (inside), ~110 m (hysteresis band), ~150 m (beyond slack), ~1000 m
 * (far outside). Latitudes precomputed from a 111320 m/deg flat-north approximation; the
 * engine itself measures with haversine.
 */
class ProximityEngineTest {

    private val centerLat = -34.9000
    private val centerLon = 138.6000

    // North offsets from the centre at the named ground distances.
    private val latInside = -34.8995508 // ~50 m  -> inside radius
    private val latBand = -34.8990119 // ~110 m -> in (radius, radius*slack]
    private val latBeyond = -34.8986525 // ~150 m -> beyond radius*slack
    private val latFar = -34.8910169 // ~1000 m -> far outside

    private fun engine(): ProximityEngine = ProximityEngine().apply {
        setTargets(
            listOf(
                ProximityTarget(
                    id = "alpha",
                    latitude = centerLat,
                    longitude = centerLon,
                    radiusMeters = 100.0,
                ),
            ),
        )
    }

    // --- enter ---------------------------------------------------------------

    @Test
    fun update_enteringRadius_emitsEnterOnce() {
        val engine = engine()
        val events = engine.update(latInside, centerLon)
        assertThat(events).containsExactly(ProximityEvent.Enter("alpha"))
        assertThat(engine.isInside("alpha")).isTrue()
    }

    @Test
    fun update_startingOutside_emitsNothing() {
        val engine = engine()
        assertThat(engine.update(latFar, centerLon)).isEmpty()
        assertThat(engine.isInside("alpha")).isFalse()
    }

    @Test
    fun update_atExactCentre_emitsEnter() {
        val engine = engine()
        assertThat(engine.update(centerLat, centerLon))
            .containsExactly(ProximityEvent.Enter("alpha"))
    }

    // --- no duplicate enter --------------------------------------------------

    @Test
    fun update_alreadyInside_doesNotReEnter() {
        val engine = engine()
        engine.update(latInside, centerLon) // Enter
        // Further inside-radius fixes produce no events.
        assertThat(engine.update(centerLat, centerLon)).isEmpty()
        assertThat(engine.update(latInside, centerLon)).isEmpty()
    }

    // --- hysteresis band (no exit between radius and radius*slack) -----------

    @Test
    fun update_insideThenHysteresisBand_doesNotExit() {
        val engine = engine()
        engine.update(latInside, centerLon) // Enter
        // 110 m: past the radius but within radius*slack (120 m) -> still inside, no event.
        assertThat(engine.update(latBand, centerLon)).isEmpty()
        assertThat(engine.isInside("alpha")).isTrue()
    }

    @Test
    fun update_bandDoesNotEnterFromOutside() {
        val engine = engine()
        // Approaching only into the band (never within the radius) must NOT Enter:
        // entry requires crossing the radius itself, not merely the slack ring.
        assertThat(engine.update(latBand, centerLon)).isEmpty()
        assertThat(engine.isInside("alpha")).isFalse()
    }

    // --- exit ----------------------------------------------------------------

    @Test
    fun update_beyondSlack_emitsExitOnce() {
        val engine = engine()
        engine.update(latInside, centerLon) // Enter
        val events = engine.update(latBeyond, centerLon)
        assertThat(events).containsExactly(ProximityEvent.Exit("alpha"))
        assertThat(engine.isInside("alpha")).isFalse()
    }

    @Test
    fun update_exitThenReEnter_emitsBothCrossings() {
        val engine = engine()
        engine.update(latInside, centerLon) // Enter
        engine.update(latBeyond, centerLon) // Exit
        // Coming back inside fires a fresh Enter.
        assertThat(engine.update(latInside, centerLon))
            .containsExactly(ProximityEvent.Enter("alpha"))
    }

    @Test
    fun update_fullHysteresisCycle_entersOnceAndExitsOnce() {
        val engine = engine()
        // inside -> band -> back inside -> band -> beyond.
        assertThat(engine.update(latInside, centerLon))
            .containsExactly(ProximityEvent.Enter("alpha"))
        assertThat(engine.update(latBand, centerLon)).isEmpty()
        assertThat(engine.update(latInside, centerLon)).isEmpty()
        assertThat(engine.update(latBand, centerLon)).isEmpty()
        assertThat(engine.update(latBeyond, centerLon))
            .containsExactly(ProximityEvent.Exit("alpha"))
    }

    // --- multiple targets ----------------------------------------------------

    @Test
    fun update_multipleTargets_areIndependent() {
        val engine = ProximityEngine()
        // alpha near our centre; beta ~14 km away (far from every alpha test point).
        engine.setTargets(
            listOf(
                ProximityTarget("alpha", centerLat, centerLon, 100.0),
                ProximityTarget("beta", -35.0000, 138.7000, 100.0),
            ),
        )
        // Enter alpha only.
        assertThat(engine.update(latInside, centerLon))
            .containsExactly(ProximityEvent.Enter("alpha"))
        assertThat(engine.isInside("alpha")).isTrue()
        assertThat(engine.isInside("beta")).isFalse()

        // Move to beta's centre — it is ~14 km from alpha (radius 100 m), so we
        // necessarily LEAVE alpha (Exit) and ENTER beta. Each target's event is
        // driven only by its own distance: that is the independence being verified.
        assertThat(engine.update(-35.0000, 138.7000))
            .containsExactly(ProximityEvent.Exit("alpha"), ProximityEvent.Enter("beta"))
        assertThat(engine.isInside("alpha")).isFalse()
        assertThat(engine.isInside("beta")).isTrue()
    }

    @Test
    fun update_multipleTargets_canEnterAndExitInOneUpdate() {
        val engine = ProximityEngine()
        engine.setTargets(
            listOf(
                ProximityTarget("alpha", centerLat, centerLon, 100.0),
                ProximityTarget("beta", -35.0000, 138.7000, 100.0),
            ),
        )
        // Seed: inside alpha.
        engine.update(latInside, centerLon)
        assertThat(engine.isInside("alpha")).isTrue()
        // Jump to beta's centre: leaves alpha (beyond slack) and enters beta in one call.
        val events = engine.update(-35.0000, 138.7000)
        assertThat(events).containsExactly(
            ProximityEvent.Exit("alpha"),
            ProximityEvent.Enter("beta"),
        )
    }

    // --- setTargets / state management ---------------------------------------

    @Test
    fun update_noTargets_emitsNothing() {
        val engine = ProximityEngine()
        assertThat(engine.update(centerLat, centerLon)).isEmpty()
    }

    @Test
    fun setTargets_preservesMembershipForSurvivingIds_dropsRemovedSilently() {
        val engine = engine()
        engine.update(latInside, centerLon) // inside alpha
        assertThat(engine.isInside("alpha")).isTrue()

        // Re-set with alpha still present (membership kept) and a new gamma.
        engine.setTargets(
            listOf(
                ProximityTarget("alpha", centerLat, centerLon, 100.0),
                ProximityTarget("gamma", -36.0, 140.0, 100.0),
            ),
        )
        assertThat(engine.isInside("alpha")).isTrue()
        // Still inside alpha => no duplicate Enter on the next in-radius update.
        assertThat(engine.update(latInside, centerLon)).isEmpty()

        // Removing alpha drops its membership without emitting a synthetic Exit.
        engine.setTargets(listOf(ProximityTarget("gamma", -36.0, 140.0, 100.0)))
        assertThat(engine.isInside("alpha")).isFalse()
    }

    @Test
    fun setTargets_duplicateIds_keepLastOccurrence() {
        val engine = ProximityEngine()
        engine.setTargets(
            listOf(
                ProximityTarget("dup", -10.0, 10.0, 100.0), // far from our centre
                ProximityTarget("dup", centerLat, centerLon, 100.0), // wins
            ),
        )
        // The surviving "dup" is the one at our centre, so an in-radius fix enters it.
        assertThat(engine.update(latInside, centerLon))
            .containsExactly(ProximityEvent.Enter("dup"))
    }

    @Test
    fun clear_forgetsTargetsAndMembership() {
        val engine = engine()
        engine.update(latInside, centerLon)
        engine.clear()
        assertThat(engine.isInside("alpha")).isFalse()
        assertThat(engine.update(latInside, centerLon)).isEmpty()
    }

    // --- guards --------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun setTargets_rejectsNonPositiveRadius() {
        ProximityEngine().setTargets(
            listOf(ProximityTarget("bad", centerLat, centerLon, 0.0)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejectsSlackBelowOne() {
        ProximityEngine(exitSlack = 0.9)
    }
}
