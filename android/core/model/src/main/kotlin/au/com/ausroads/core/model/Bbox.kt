/*
 * Bounding box for viewport queries against traffic providers and map tile sources.
 * Order is (west, south, east, north) in WGS84 degrees.
 */
package au.com.ausroads.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Bbox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(west in -180.0..180.0) { "west out of range: $west" }
        require(east in -180.0..180.0) { "east out of range: $east" }
        require(south in -90.0..90.0) { "south out of range: $south" }
        require(north in -90.0..90.0) { "north out of range: $north" }
        require(west <= east) { "west ($west) must be <= east ($east)" }
        require(south <= north) { "south ($south) must be <= north ($north)" }
    }

    /** South Australia state bounding box (rough). */
    companion object {
        val AUSTRALIA_SA = Bbox(
            west = 129.0,
            south = -38.2,
            east = 141.1,
            north = -25.9,
        )

        val ADELAIDE_METRO = Bbox(
            west = 138.4,
            south = -35.2,
            east = 139.0,
            north = -34.6,
        )
    }
}
