/*
 * No-op LiveTrafficProvider that always returns an empty feed. v0.1 ships with this
 * provider only; v0.2 replaces it (or supplements it) with the AU-SA Traffic SA adapter.
 */
package au.com.ausroads.traffic.provider.stub

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.traffic.provider.FetchResult
import au.com.ausroads.traffic.provider.LiveTrafficProvider

class StubLiveTrafficProvider : LiveTrafficProvider {
    override val regionCode: String = "STUB"
    override val displayName: String = "Stub provider (no traffic)"
    override val supportedBbox: Bbox = Bbox(
        west = -180.0,
        south = -90.0,
        east = 180.0,
        north = 90.0,
    )

    override suspend fun fetchEvents(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = FetchResult.Empty

    override suspend fun fetchClosures(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = FetchResult.Empty
}
