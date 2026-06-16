package au.com.ausroads.feature.trip

import au.com.ausroads.core.geo.FuelStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FuelPlannerTest {

    // FuelRange.fuelStatus semantics (reserve r):
    //   range < nearest                  -> WONT_MAKE_IT
    //   nearest <= range < nearest + r   -> GET_FUEL_SOON
    //   range >= nearest + r             -> OK

    @Test
    fun nearestComfortablyWithinRange_isOk() {
        // nearest 100, range 400, reserve 50 => 400 >= 150 => OK.
        val plan = FuelPlanner.plan(rangeKm = 400.0, servoDistancesKm = listOf(100.0, 250.0))
        assertThat(plan.status).isEqualTo(FuelStatus.OK)
        assertThat(plan.nearestServoKm).isEqualTo(100.0)
    }

    @Test
    fun nearestReachableButEatsReserve_isGetFuelSoon() {
        // nearest 380, range 400, reserve 50 => 400 < 430 but >= 380 => GET_FUEL_SOON.
        val plan = FuelPlanner.plan(rangeKm = 400.0, servoDistancesKm = listOf(380.0, 500.0))
        assertThat(plan.status).isEqualTo(FuelStatus.GET_FUEL_SOON)
        assertThat(plan.nearestServoKm).isEqualTo(380.0)
    }

    @Test
    fun nearestBeyondRange_isWontMakeIt() {
        // nearest 450 > range 400 => WONT_MAKE_IT.
        val plan = FuelPlanner.plan(rangeKm = 400.0, servoDistancesKm = listOf(450.0, 600.0))
        assertThat(plan.status).isEqualTo(FuelStatus.WONT_MAKE_IT)
        assertThat(plan.nearestServoKm).isEqualTo(450.0)
    }

    @Test
    fun emptyServoList_isWontMakeIt_withNullNearestAndZeroReachable() {
        val plan = FuelPlanner.plan(rangeKm = 400.0, servoDistancesKm = emptyList())
        assertThat(plan.status).isEqualTo(FuelStatus.WONT_MAKE_IT)
        assertThat(plan.nearestServoKm).isNull()
        assertThat(plan.reachableServoCount).isEqualTo(0)
    }

    @Test
    fun reachableServoCount_countsThoseWithinRange() {
        // range 400: servos at 100, 250, 400 are reachable (<=400); 500, 700 are not.
        val plan = FuelPlanner.plan(
            rangeKm = 400.0,
            servoDistancesKm = listOf(100.0, 250.0, 400.0, 500.0, 700.0),
        )
        assertThat(plan.reachableServoCount).isEqualTo(3)
    }

    @Test
    fun reachableServoCount_boundaryAtExactRange_isReachable() {
        // A servo exactly at the range limit counts as reachable (<= range).
        val plan = FuelPlanner.plan(rangeKm = 200.0, servoDistancesKm = listOf(200.0))
        assertThat(plan.reachableServoCount).isEqualTo(1)
    }

    @Test
    fun nearestTakenRegardlessOfInputOrder() {
        // Unsorted input: nearest must still be the minimum.
        val plan = FuelPlanner.plan(rangeKm = 1000.0, servoDistancesKm = listOf(300.0, 80.0, 220.0))
        assertThat(plan.nearestServoKm).isEqualTo(80.0)
    }

    @Test
    fun boundary_nearestExactlyAtRange_isGetFuelSoon() {
        // range == nearest (200): not < nearest, and 200 < 200+50 => GET_FUEL_SOON.
        val plan = FuelPlanner.plan(rangeKm = 200.0, servoDistancesKm = listOf(200.0))
        assertThat(plan.status).isEqualTo(FuelStatus.GET_FUEL_SOON)
    }

    @Test
    fun boundary_rangeExactlyNearestPlusReserve_isOk() {
        // range 250 == nearest 200 + reserve 50 => not < sum => OK.
        val plan = FuelPlanner.plan(rangeKm = 250.0, servoDistancesKm = listOf(200.0))
        assertThat(plan.status).isEqualTo(FuelStatus.OK)
    }

    @Test
    fun customReserve_isHonoured() {
        // nearest 300, range 350, reserve 100 => 350 < 400 => GET_FUEL_SOON
        // (would be OK with the default 50 reserve, since 350 >= 350).
        val plan = FuelPlanner.plan(rangeKm = 350.0, servoDistancesKm = listOf(300.0), reserveKm = 100.0)
        assertThat(plan.status).isEqualTo(FuelStatus.GET_FUEL_SOON)
    }

    @Test
    fun planEchoesRange() {
        val plan = FuelPlanner.plan(rangeKm = 425.5, servoDistancesKm = listOf(100.0))
        assertThat(plan.rangeKm).isEqualTo(425.5)
    }

    @Test
    fun negativeDistance_treatedAsMagnitude() {
        // Defensive: a -120 distance is read as 120 km away.
        val plan = FuelPlanner.plan(rangeKm = 400.0, servoDistancesKm = listOf(-120.0))
        assertThat(plan.nearestServoKm).isEqualTo(120.0)
        assertThat(plan.reachableServoCount).isEqualTo(1)
        assertThat(plan.status).isEqualTo(FuelStatus.OK)
    }
}
