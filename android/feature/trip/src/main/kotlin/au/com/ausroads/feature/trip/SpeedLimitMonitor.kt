/*
 * Stateful over-speed alerting with hysteresis.
 *
 * Raw GPS speed jitters by a few km/h even at a steady cruise, so a naive
 * "speed > limit" test flaps between alert states once per second. This monitor
 * adds two thresholds: an entry buffer above the limit (you only become OVER once
 * clearly over) and an exit point back at the limit (you only clear once genuinely
 * back to legal). The gap between them is the hysteresis band that absorbs jitter.
 */
package au.com.ausroads.feature.trip

/** Over-speed alert level reported by [SpeedLimitMonitor]. */
enum class SpeedAlert {
    /** At or below the limit (or no limit known). */
    NONE,

    /** Within the configured margin below the limit, but not over. */
    APPROACHING,

    /** Driving over the limit (latched until speed returns to the limit). */
    OVER,
}

/**
 * Tracks the over-speed [SpeedAlert] for a stream of speed samples against a
 * (possibly changing, possibly unknown) speed limit.
 *
 * Hysteresis: the monitor enters [SpeedAlert.OVER] only once speed exceeds
 * `limit + overBufferKmh`, and stays OVER until speed drops back to `<= limit`.
 * While OVER, speeds in the `(limit, limit + overBuffer]` band hold the OVER state
 * rather than clearing it, so GPS jitter around the limit does not flap the alert.
 *
 * Not thread-safe; drive it from a single coroutine/looper.
 *
 * @param approachingMarginKmh when speed is within this many km/h below the limit
 *   (but not over), report [SpeedAlert.APPROACHING]. Default `0.0` disables the
 *   APPROACHING state entirely.
 * @param overBufferKmh how far above the limit speed must rise before entering
 *   [SpeedAlert.OVER]. Default `5.0`.
 */
class SpeedLimitMonitor(
    private val approachingMarginKmh: Double = 0.0,
    private val overBufferKmh: Double = 5.0,
) {
    init {
        require(approachingMarginKmh >= 0.0) {
            "approachingMarginKmh must be >= 0: $approachingMarginKmh"
        }
        require(overBufferKmh >= 0.0) { "overBufferKmh must be >= 0: $overBufferKmh" }
    }

    private var previous: SpeedAlert = SpeedAlert.NONE

    /** The alert reported by the most recent [update] (starts at [SpeedAlert.NONE]). */
    val current: SpeedAlert
        get() = previous

    /**
     * Feed the latest [speedKmh] and current [limitKmh] (`null` = limit unknown), and
     * return the new alert level. Updates internal state for the next call.
     *
     * A `null` limit always yields [SpeedAlert.NONE] and resets the latch, so a fresh
     * limit starts cleanly.
     */
    fun update(speedKmh: Double, limitKmh: Int?): SpeedAlert {
        val next = classify(speedKmh, limitKmh, previous)
        previous = next
        return next
    }

    /** Reset the latched state back to [SpeedAlert.NONE]. */
    fun reset() {
        previous = SpeedAlert.NONE
    }

    /**
     * Pure classification of one sample given the [prev] state, with no side effects.
     *
     * Hysteresis rules:
     *  - `null` limit -> [SpeedAlert.NONE].
     *  - If [prev] was [SpeedAlert.OVER], remain OVER while speed stays above the limit
     *    (i.e. anywhere in the buffer band); clear only when speed `<= limit`.
     *  - Otherwise enter [SpeedAlert.OVER] only once speed `> limit + overBufferKmh`.
     *  - When not OVER, report [SpeedAlert.APPROACHING] if speed is within
     *    [approachingMarginKmh] below the limit (and the margin is enabled), else
     *    [SpeedAlert.NONE].
     */
    fun classify(speedKmh: Double, limitKmh: Int?, prev: SpeedAlert): SpeedAlert {
        if (limitKmh == null) return SpeedAlert.NONE
        val limit = limitKmh.toDouble()

        if (prev == SpeedAlert.OVER) {
            // Latched: only clear once back to or below the limit.
            return if (speedKmh > limit) SpeedAlert.OVER else belowLimitAlert(speedKmh, limit)
        }

        // Not latched: require the full buffer above the limit to engage.
        if (speedKmh > limit + overBufferKmh) return SpeedAlert.OVER
        return belowLimitAlert(speedKmh, limit)
    }

    /**
     * Classify a speed that is known to be at/below the over-speed latch: either
     * [SpeedAlert.APPROACHING] (within the enabled margin below [limit]) or
     * [SpeedAlert.NONE].
     */
    private fun belowLimitAlert(speedKmh: Double, limit: Double): SpeedAlert {
        if (approachingMarginKmh > 0.0 &&
            speedKmh <= limit &&
            speedKmh >= limit - approachingMarginKmh
        ) {
            return SpeedAlert.APPROACHING
        }
        return SpeedAlert.NONE
    }
}
