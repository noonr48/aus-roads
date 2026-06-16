package au.com.ausroads.traffic.congestion

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.GeoPoint
import kotlinx.coroutines.flow.Flow

interface CongestionProvider {
    val displayName: String
    val costPerRequest: Double  // USD, displayed in About screen

    fun observeCongestion(bbox: Bbox): Flow<List<CongestionSegment>>
    suspend fun queryCongestion(point: GeoPoint): CongestionLevel
}

data class CongestionSegment(
    val roadName: String,
    val coordinates: List<GeoPoint>,
    val level: CongestionLevel,
    val speedKmh: Double,
    val freeFlowSpeedKmh: Double,
)

enum class CongestionLevel(val colorHex: String) {
    FREE("#4CAF50"),      // Green - free flow
    LIGHT("#8BC34A"),     // Light green
    MODERATE("#FF9800"),  // Orange
    HEAVY("#F44336"),     // Red
    SEVERE("#9C27B0"),    // Purple - standstill
}
