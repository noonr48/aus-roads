/*
 * A single WGS84 geographic point.
 */
package au.com.ausroads.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GeoPoint(
    /** Longitude in degrees, -180..180. */
    val longitude: Double,
    /** Latitude in degrees, -90..90. */
    val latitude: Double,
) {
    init {
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
    }
}
