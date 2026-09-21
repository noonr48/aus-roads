package au.com.ausroads.ui.map

/** Reject old cached fixes and fixes captured before an explicit refresh. */
internal fun isLocationFixFresh(
    fixTimeMillis: Long,
    nowMillis: Long,
    minimumFixTimeMillis: Long = 0L,
    maxAgeMillis: Long = DEFAULT_MAX_LOCATION_AGE_MS,
): Boolean {
    val ageMillis = nowMillis - fixTimeMillis
    return fixTimeMillis >= minimumFixTimeMillis && ageMillis in 0..maxAgeMillis
}

/**
 * Android can throttle approximate-location updates to a multi-minute cadence
 * (ten minutes on the acceptance-test device). Keep those fixes visible long
 * enough to bridge that cadence while preserving strict precise-fix expiry.
 */
internal fun maximumLocationFixAgeMillis(isPreciseLocationGranted: Boolean): Long =
    if (isPreciseLocationGranted) {
        DEFAULT_MAX_LOCATION_AGE_MS
    } else {
        APPROXIMATE_MAX_LOCATION_AGE_MS
    }

/**
 * Return the time used for a fix delivered by an active subscription.
 * Approximate callbacks can retain a privacy-aged capture timestamp, so a
 * callback received now is presented as live only after its source fix passes
 * the wider approximate-location freshness gate.
 */
internal fun liveLocationDisplayTimeMillis(
    fixTimeMillis: Long,
    receivedAtMillis: Long,
    isPreciseLocationGranted: Boolean,
): Long? {
    val maxAgeMillis = maximumLocationFixAgeMillis(isPreciseLocationGranted)
    if (!isLocationFixFresh(fixTimeMillis, receivedAtMillis, maxAgeMillis = maxAgeMillis)) {
        return null
    }
    return if (isPreciseLocationGranted) fixTimeMillis else receivedAtMillis
}

/** Delay until a currently-fresh fix must disappear from the UI, or null when already stale. */
internal fun remainingLocationFreshnessMillis(
    fixTimeMillis: Long,
    nowMillis: Long,
    maxAgeMillis: Long = DEFAULT_MAX_LOCATION_AGE_MS,
): Long? {
    if (!isLocationFixFresh(fixTimeMillis, nowMillis, maxAgeMillis = maxAgeMillis)) return null
    return maxAgeMillis - (nowMillis - fixTimeMillis) + 1L
}

/** Select the most accurate candidate only after stale and invalid fixes are excluded. */
internal fun <T> selectBestFreshFix(
    candidates: Iterable<T>,
    nowMillis: Long,
    fixTimeMillis: (T) -> Long,
    accuracyMeters: (T) -> Float?,
    maxAgeMillis: Long = DEFAULT_MAX_LOCATION_AGE_MS,
): T? = candidates
    .filter { candidate ->
        isLocationFixFresh(
            fixTimeMillis = fixTimeMillis(candidate),
            nowMillis = nowMillis,
            maxAgeMillis = maxAgeMillis,
        )
    }
    .minByOrNull { candidate ->
        accuracyMeters(candidate)
            ?.takeIf { accuracy -> accuracy >= 0f && accuracy.isFinite() }
            ?: Float.POSITIVE_INFINITY
    }

internal const val DEFAULT_MAX_LOCATION_AGE_MS = 120_000L
internal const val APPROXIMATE_MAX_LOCATION_AGE_MS = 15 * 60_000L

/** Cancellation boundary for a one-shot camera recenter request. */
internal fun interface RecenterRequest {
    fun cancel()
}

internal val NoOpRecenterRequest = RecenterRequest { }

/**
 * Gives MapScreen sole ownership of its active recenter request. Replacing a
 * request cancels the previous one before the next provider call can begin.
 */
internal class RecenterRequestOwner {
    private var active: RecenterRequest? = null

    fun replace(start: () -> RecenterRequest) {
        cancel()
        active = start()
    }

    fun cancel() {
        active?.cancel()
        active = null
    }
}
