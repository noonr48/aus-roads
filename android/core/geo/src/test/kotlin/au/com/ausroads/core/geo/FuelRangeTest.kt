package au.com.ausroads.core.geo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FuelRangeTest {

    @Test
    fun rangeKm_basicArithmetic() {
        // 60 L at 8 L/100km => 750 km.
        assertThat(FuelRange.rangeKm(60.0, 8.0)).isWithin(1e-9).of(750.0)
    }

    @Test
    fun rangeKm_anotherRatio() {
        // 50 L at 10 L/100km => 500 km.
        assertThat(FuelRange.rangeKm(50.0, 10.0)).isWithin(1e-9).of(500.0)
    }

    @Test
    fun rangeKm_zeroEconomy_returnsZero() {
        assertThat(FuelRange.rangeKm(40.0, 0.0)).isEqualTo(0.0)
    }

    @Test
    fun rangeKm_negativeEconomy_returnsZero() {
        assertThat(FuelRange.rangeKm(40.0, -5.0)).isEqualTo(0.0)
    }

    @Test
    fun rangeKm_zeroFuel_returnsZero() {
        assertThat(FuelRange.rangeKm(0.0, 8.0)).isEqualTo(0.0)
    }

    @Test
    fun fuelStatus_ok_whenRangeBeyondDistancePlusReserve() {
        // range 500, need 300, reserve 50 => 500 >= 350 => OK
        assertThat(FuelRange.fuelStatus(500.0, 300.0, 50.0)).isEqualTo(FuelStatus.OK)
    }

    @Test
    fun fuelStatus_wontMakeIt_whenRangeBelowDistance() {
        assertThat(FuelRange.fuelStatus(250.0, 300.0, 50.0)).isEqualTo(FuelStatus.WONT_MAKE_IT)
    }

    @Test
    fun fuelStatus_getFuelSoon_betweenDistanceAndDistancePlusReserve() {
        // range 320 is >= 300 (makes it) but < 350 (eats reserve) => GET_FUEL_SOON
        assertThat(FuelRange.fuelStatus(320.0, 300.0, 50.0)).isEqualTo(FuelStatus.GET_FUEL_SOON)
    }

    @Test
    fun fuelStatus_boundary_exactlyAtDistance_isGetFuelSoon() {
        // range == distance: NOT < distance, so not WONT_MAKE_IT.
        // range (300) < distance+reserve (350) => GET_FUEL_SOON.
        assertThat(FuelRange.fuelStatus(300.0, 300.0, 50.0)).isEqualTo(FuelStatus.GET_FUEL_SOON)
    }

    @Test
    fun fuelStatus_boundary_exactlyAtDistancePlusReserve_isOk() {
        // range == distance + reserve: NOT < that sum => OK.
        assertThat(FuelRange.fuelStatus(350.0, 300.0, 50.0)).isEqualTo(FuelStatus.OK)
    }

    @Test
    fun fuelStatus_zeroReserve_atDistance_isOk() {
        // With no reserve, range == distance is OK (not < distance, not < distance+0).
        assertThat(FuelRange.fuelStatus(300.0, 300.0, 0.0)).isEqualTo(FuelStatus.OK)
    }
}
