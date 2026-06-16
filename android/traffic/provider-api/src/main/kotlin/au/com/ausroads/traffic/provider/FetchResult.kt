/*
 * Result of a single fetch against a LiveTrafficProvider. Carries enough metadata for
 * the consumer to do conditional GETs (ETag) and to know when to poll again.
 */
package au.com.ausroads.traffic.provider

import kotlinx.datetime.Instant
import kotlin.time.Duration

data class FetchResult(
    val events: List<LiveTrafficEvent>,
    /**
     * Server-reported "fetched at" if exposed. Used to drive the
     * "Updated N min ago" pill in the UI.
     */
    val serverTimestamp: Instant?,
    /**
     * Hint for how long the consumer should wait before polling again. Providers may
     * set this to match the server's Cache-Control max-age if available.
     */
    val cacheMaxAge: Duration,
    /**
     * ETag from the upstream HTTP response, or null. Pass back via `ifNoneMatch` on the
     * next call to get a 304-style skip.
     */
    val etag: String?,
    val lastModified: String?,
) {
    companion object {
        val Empty = FetchResult(
            events = emptyList(),
            serverTimestamp = null,
            cacheMaxAge = Duration.ZERO,
            etag = null,
            lastModified = null,
        )
    }
}
