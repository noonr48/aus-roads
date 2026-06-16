/*
 * Stateful geofence-style proximity tracker.
 *
 * Given a set of circular targets, each position update emits Enter events for
 * targets just crossed into and Exit events for targets just left. Exit uses an
 * outer slack radius (radius * exitSlack) so that GPS jitter at the boundary cannot
 * rapidly toggle a target between inside and outside.
 */
package au.com.ausroads.feature.trip

import au.com.ausroads.core.geo.MeasureGeometry
import au.com.ausroads.core.model.GeoPoint

/**
 * A circular geofence target.
 *
 * @property id caller-chosen stable identifier (echoed back in events).
 * @property latitude target centre latitude, degrees.
 * @property longitude target centre longitude, degrees.
 * @property radiusMeters trigger radius in metres (must be > 0).
 */
data class ProximityTarget(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
)

/** An entry/exit transition for a single [ProximityTarget]. */
sealed interface ProximityEvent {
    /** The target id this event concerns. */
    val targetId: String

    /** Position has crossed into the target's radius. */
    data class Enter(override val targetId: String) : ProximityEvent

    /** Position has left the target (crossed beyond `radius * exitSlack`). */
    data class Exit(override val targetId: String) : ProximityEvent
}

/**
 * Tracks inside/outside membership for a set of [ProximityTarget]s as the user moves.
 *
 * Hysteresis: a target is entered when the user comes within its `radiusMeters`, and
 * left only once the user is beyond `radiusMeters * exitSlack`. Positions in the
 * `(radius, radius * exitSlack]` band hold the current membership, so a fix hovering
 * on the boundary does not emit a stream of Enter/Exit pairs.
 *
 * Not thread-safe; drive from a single coroutine/looper.
 *
 * @param exitSlack multiplier (>= 1.0) applied to each target's radius to obtain the
 *   exit threshold. Default `1.2`.
 */
class ProximityEngine(
    private val exitSlack: Double = 1.2,
) {
    init {
        require(exitSlack >= 1.0) { "exitSlack must be >= 1.0: $exitSlack" }
    }

    private var targets: List<ProximityTarget> = emptyList()

    /** Ids of targets the user is currently inside (entered, not yet exited). */
    private val inside: MutableSet<String> = mutableSetOf()

    /**
     * Replace the tracked targets. Membership for ids that survive the change is
     * preserved; ids no longer present are dropped silently (no synthetic Exit). New
     * targets start "outside" and will Enter on the next [update] if already in range.
     *
     * Duplicate ids in [newTargets] keep the last occurrence.
     */
    fun setTargets(newTargets: List<ProximityTarget>) {
        require(newTargets.all { it.radiusMeters > 0.0 }) {
            "every ProximityTarget.radiusMeters must be > 0"
        }
        val deduped = newTargets.associateBy { it.id }
        targets = deduped.values.toList()
        inside.retainAll(deduped.keys)
    }

    /** Forget all targets and membership. */
    fun clear() {
        targets = emptyList()
        inside.clear()
    }

    /** True if the user is currently inside the target with this [targetId]. */
    fun isInside(targetId: String): Boolean = targetId in inside

    /**
     * Update the current position and return the resulting transitions (possibly empty),
     * in target order. At most one event per target per call: an outside->inside crossing
     * yields [ProximityEvent.Enter]; an inside->beyond-slack crossing yields
     * [ProximityEvent.Exit].
     */
    fun update(latitude: Double, longitude: Double): List<ProximityEvent> {
        if (targets.isEmpty()) return emptyList()
        val here = GeoPoint(longitude = longitude, latitude = latitude)

        val events = mutableListOf<ProximityEvent>()
        for (target in targets) {
            val center = GeoPoint(longitude = target.longitude, latitude = target.latitude)
            val distance = MeasureGeometry.haversineMeters(here, center)
            val currentlyInside = target.id in inside

            if (currentlyInside) {
                if (distance > target.radiusMeters * exitSlack) {
                    inside.remove(target.id)
                    events += ProximityEvent.Exit(target.id)
                }
            } else {
                if (distance <= target.radiusMeters) {
                    inside.add(target.id)
                    events += ProximityEvent.Enter(target.id)
                }
            }
        }
        return events
    }
}
