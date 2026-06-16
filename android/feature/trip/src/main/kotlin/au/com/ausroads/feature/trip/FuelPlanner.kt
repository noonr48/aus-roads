/*
 * FuelPlanner — pure fuel / servo decision logic for the outback fuel-range feature.
 *
 * Given the vehicle's estimated remaining range and the distances to nearby service
 * stations ("servos"), it advises whether the driver is OK, should get fuel soon, or
 * won't reach the next servo — reusing core:geo's FuelRange thresholds so the advice is
 * consistent with the rest of the app. The plan function is PURE (it takes the distances
 * directly), which makes it fully unit-testable; [ServoSource] is only the data port that
 * the real POI-search implementation satisfies later.
 */
package au.com.ausroads.feature.trip

import au.com.ausroads.core.geo.FuelRange
import au.com.ausroads.core.geo.FuelStatus

/**
 * Data port supplying along-route / great-circle distances (in kilometres) to nearby
 * service stations. The production implementation queries the offline POI search; tests
 * and [FuelPlanner.plan] do not need it — they take the distances directly.
 */
interface ServoSource {
    /**
     * Distances in kilometres to the nearest service stations from ([latitude],
     * [longitude]), sorted ascending (nearest first), at most [limit] of them. An empty
     * list means no known servo nearby.
     */
    suspend fun nearestFuelKmAlong(latitude: Double, longitude: Double, limit: Int): List<Double>
}

/**
 * The result of a fuel plan.
 *
 * @property status overall advisory ([FuelStatus]); [FuelStatus.WONT_MAKE_IT] when no servo
 *   is reachable on the current range.
 * @property rangeKm the estimated range, in km, the plan was computed against (echoed back).
 * @property nearestServoKm distance to the nearest known servo in km, or `null` when no
 *   servo distances were supplied.
 * @property reachableServoCount how many of the supplied servos lie within [rangeKm].
 */
data class FuelPlan(
    val status: FuelStatus,
    val rangeKm: Double,
    val nearestServoKm: Double?,
    val reachableServoCount: Int,
)

/** Pure fuel-planning logic. No state; [ServoSource] is the only (separate) I/O port. */
object FuelPlanner {

    /**
     * Decide the fuel situation.
     *
     * The advisory is computed against the NEAREST servo via [FuelRange.fuelStatus] with the
     * given [reserveKm] safety margin:
     * - nearest within range, beyond the reserve ⇒ [FuelStatus.OK];
     * - nearest reachable but only by eating into the reserve ⇒ [FuelStatus.GET_FUEL_SOON];
     * - nearest farther than the range ⇒ [FuelStatus.WONT_MAKE_IT].
     *
     * When [servoDistancesKm] is empty there is no reachable fuel, so the status is
     * [FuelStatus.WONT_MAKE_IT], [FuelPlan.nearestServoKm] is `null`, and the reachable
     * count is 0.
     *
     * Negative distances are treated as their magnitude (a servo cannot be a negative
     * distance away); this is defensive — callers supply non-negative, sorted distances.
     *
     * @param rangeKm estimated remaining range in km (e.g. from [FuelRange.rangeKm]).
     * @param servoDistancesKm distances in km to nearby servos; need not be pre-sorted
     *   (the nearest is taken explicitly).
     * @param reserveKm safety reserve in km kept in hand when classifying.
     */
    fun plan(
        rangeKm: Double,
        servoDistancesKm: List<Double>,
        reserveKm: Double = 50.0,
    ): FuelPlan {
        val sanitized = servoDistancesKm.map { kotlin.math.abs(it) }

        if (sanitized.isEmpty()) {
            return FuelPlan(
                status = FuelStatus.WONT_MAKE_IT,
                rangeKm = rangeKm,
                nearestServoKm = null,
                reachableServoCount = 0,
            )
        }

        val nearest = sanitized.min()
        val reachableCount = sanitized.count { it <= rangeKm }
        val status = FuelRange.fuelStatus(
            rangeKm = rangeKm,
            distanceToNextFuelKm = nearest,
            reserveKm = reserveKm,
        )

        return FuelPlan(
            status = status,
            rangeKm = rangeKm,
            nearestServoKm = nearest,
            reachableServoCount = reachableCount,
        )
    }
}
