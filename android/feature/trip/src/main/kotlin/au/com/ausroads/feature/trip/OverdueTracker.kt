/*
 * OverdueTracker — pure state machine for the trip-share / overdue check-in feature.
 *
 * Given a planned arrival time and the current time, it classifies the traveller as
 * on-time, due-soon (a window around the ETA), overdue (past the grace period), or
 * arrived. No timers, no I/O — the caller supplies `now`, so the whole thing is a
 * deterministic, fully unit-testable function. A foreground service / WorkManager job
 * built later drives it by passing the wall clock and acting on the returned state.
 */
package au.com.ausroads.feature.trip

import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant

/**
 * Where a traveller stands relative to their planned arrival.
 *
 * - [ON_TIME]: comfortably before the ETA (earlier than the due-soon window).
 * - [DUE_SOON]: inside the window `[eta - dueSoon, eta + grace]` — expected to arrive
 *   around now; a good moment to prompt a check-in.
 * - [OVERDUE]: past `eta + grace` without arriving — time to raise the alarm.
 * - [ARRIVED]: the traveller has checked in as arrived; overrides everything else.
 */
enum class OverdueState {
    ON_TIME,
    DUE_SOON,
    OVERDUE,
    ARRIVED,
}

/**
 * A single planned check-in.
 *
 * @property plannedEtaUtc the expected arrival time (UTC instant).
 * @property graceMinutes minutes after the ETA before the traveller is considered
 *   overdue. Must be >= 0.
 * @property dueSoonMinutes minutes before the ETA at which the state flips to
 *   [OverdueState.DUE_SOON]. Must be >= 0.
 * @property arrived whether the traveller has confirmed arrival; when true the state
 *   is always [OverdueState.ARRIVED].
 */
data class CheckIn(
    val plannedEtaUtc: Instant,
    val graceMinutes: Long = 30,
    val dueSoonMinutes: Long = 15,
    val arrived: Boolean = false,
)

/** Pure classifier for [CheckIn] state at a given instant. */
object OverdueTracker {

    /**
     * Classify [checkIn] as of [now].
     *
     * Rules, in order:
     * 1. [checkIn] marked arrived ⇒ [OverdueState.ARRIVED].
     * 2. `now < eta - dueSoon` ⇒ [OverdueState.ON_TIME].
     * 3. `eta - dueSoon <= now <= eta + grace` ⇒ [OverdueState.DUE_SOON] (boundaries
     *    inclusive on both ends).
     * 4. `now > eta + grace` ⇒ [OverdueState.OVERDUE].
     *
     * Negative `graceMinutes`/`dueSoonMinutes` are treated as 0 so the window never
     * inverts (a defensive guard; callers are expected to pass non-negative values).
     */
    fun stateAt(checkIn: CheckIn, now: Instant): OverdueState {
        if (checkIn.arrived) return OverdueState.ARRIVED

        val eta = checkIn.plannedEtaUtc
        val dueSoonStart = eta - checkIn.dueSoonMinutes.coerceAtLeast(0).minutes
        val overdueAfter = eta + checkIn.graceMinutes.coerceAtLeast(0).minutes

        return when {
            now < dueSoonStart -> OverdueState.ON_TIME
            now <= overdueAfter -> OverdueState.DUE_SOON
            else -> OverdueState.OVERDUE
        }
    }
}
