/*
 * Fuel-range estimation: how far a vehicle can travel on remaining fuel, and a
 * simple advisory status for reaching the next refuelling stop in the outback.
 */
package au.com.ausroads.core.geo

/**
 * Advisory status for whether the vehicle can reach the next known fuel stop.
 *
 * - [OK]: comfortable range, beyond the configured reserve.
 * - [GET_FUEL_SOON]: can reach the next stop, but only by eating into the reserve.
 * - [WONT_MAKE_IT]: estimated range is shorter than the distance to the next stop.
 */
enum class FuelStatus {
    OK,
    GET_FUEL_SOON,
    WONT_MAKE_IT,
}

/**
 * Pure fuel/range arithmetic. No state; all inputs are explicit.
 */
object FuelRange {

    /**
     * Estimated travel range in kilometres for [fuelLitres] of fuel at a fuel economy
     * of [economyLitresPer100Km] litres per 100 km.
     *
     * Returns 0.0 when economy is non-positive (cannot divide / undefined), or when
     * fuel is non-positive.
     */
    fun rangeKm(fuelLitres: Double, economyLitresPer100Km: Double): Double {
        if (economyLitresPer100Km <= 0.0) return 0.0
        if (fuelLitres <= 0.0) return 0.0
        return fuelLitres / economyLitresPer100Km * 100.0
    }

    /**
     * Classify the current situation given the estimated [rangeKm], the
     * [distanceToNextFuelKm] to the next known fuel stop, and a safety [reserveKm].
     *
     * - [FuelStatus.WONT_MAKE_IT] when range < distance.
     * - [FuelStatus.GET_FUEL_SOON] when range < distance + reserve.
     * - [FuelStatus.OK] otherwise.
     */
    fun fuelStatus(
        rangeKm: Double,
        distanceToNextFuelKm: Double,
        reserveKm: Double,
    ): FuelStatus = when {
        rangeKm < distanceToNextFuelKm -> FuelStatus.WONT_MAKE_IT
        rangeKm < distanceToNextFuelKm + reserveKm -> FuelStatus.GET_FUEL_SOON
        else -> FuelStatus.OK
    }
}
