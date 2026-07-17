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
): T? = candidates
    .filter { candidate -> isLocationFixFresh(fixTimeMillis(candidate), nowMillis) }
    .minByOrNull { candidate ->
        accuracyMeters(candidate)
            ?.takeIf { accuracy -> accuracy >= 0f && accuracy.isFinite() }
            ?: Float.POSITIVE_INFINITY
    }

internal const val DEFAULT_MAX_LOCATION_AGE_MS = 120_000L

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
