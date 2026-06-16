package au.com.ausroads.traffic.congestion

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class StubCongestionProvider : CongestionProvider {
    override val displayName = "No congestion data"
    override val costPerRequest = 0.0

    override fun observeCongestion(bbox: Bbox): Flow<List<CongestionSegment>> = flowOf(emptyList())
    override suspend fun queryCongestion(point: GeoPoint): CongestionLevel = CongestionLevel.FREE
}
