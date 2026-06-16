/*
 * LiveTrafficProvider — the abstraction every regional provider implements.
 * Locked in v0.2. See /home/benbi/Apps/aus-roads/docs/adr/0004-provider-abstraction.md
 * for the design rationale.
 */
package au.com.ausroads.traffic.provider

import au.com.ausroads.core.model.Bbox

interface LiveTrafficProvider {
    /**
     * Canonical region code, e.g. "AU-SA". Must be globally unique across all
     * installed providers; the consumer uses it to pick the right provider.
     */
    val regionCode: String

    /** Human-readable name for the UI: "Traffic SA", "NSW Live Traffic", etc. */
    val displayName: String

    /**
     * Hard upper bound on the bbox this provider can serve. Typically the state outline.
     * The consumer must clip requested bboxes to this before issuing a fetch.
     */
    val supportedBbox: Bbox

    /**
     * Fetch point-type events (incidents, roadworks, events) within [bbox]. If `bbox` is
     * null, the provider returns its full supported area. Pass the previous `etag` via
     * [ifNoneMatch] to enable 304-style conditional GETs.
     */
    suspend fun fetchEvents(
        bbox: Bbox?,
        ifNoneMatch: String? = null,
    ): FetchResult

    /**
     * Fetch line-type events (closures, detours) within [bbox]. Same semantics as
     * [fetchEvents]. Some providers (e.g. TomTom, HERE) return both from one call —
     * those may implement this by calling [fetchEvents] and filtering. AU-SA v0.2
     * has a separate layer for closures and implements both explicitly.
     */
    suspend fun fetchClosures(
        bbox: Bbox?,
        ifNoneMatch: String? = null,
    ): FetchResult

    /**
     * Whether this provider exposes a change-tracking feed (rare). v0.2 polling providers
     * return false. v0.7+ commercial providers may return true.
     */
    fun supportsChangeTracking(): Boolean = false
}
