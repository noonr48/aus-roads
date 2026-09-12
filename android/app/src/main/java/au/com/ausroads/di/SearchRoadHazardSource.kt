package au.com.ausroads.di

import au.com.ausroads.core.geo.MeasureGeometry
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.feature.navigation.RoadHazardCamera
import au.com.ausroads.feature.navigation.RoadHazardContext
import au.com.ausroads.feature.navigation.RoadHazardSource
import au.com.ausroads.offline.search.SearchRepository
import kotlin.math.min

/**
 * Pack-backed [RoadHazardSource]: the navigation feature module consumes the
 * port; this app-layer implementation reads the installed map pack's optional
 * `road_speed` + `road_cameras` tables through [SearchRepository].
 *
 * All lookups are offline. Cameras are returned with great-circle distances
 * from the query position so the navigation layer can rank and warn without
 * re-deriving geometry. Constructed by [RoadHazardModule].
 */
class SearchRoadHazardSource(
    private val searchRepository: SearchRepository,
) : RoadHazardSource {

    override suspend fun contextAt(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
    ): RoadHazardContext {
        val radiusDegrees = (radiusMeters / METERS_PER_DEGREE_LATITUDE)
            .coerceAtLeast(MIN_RADIUS_DEGREES)
        val here = GeoPoint(longitude = longitude, latitude = latitude)

        val speedLimit = searchRepository.maxspeedNear(
            latitude = latitude,
            longitude = longitude,
            maxDistanceDegrees = min(SPEED_SNAP_DEGREES, radiusDegrees),
        )

        val cameras = searchRepository.camerasNear(
            latitude = latitude,
            longitude = longitude,
            maxDistanceDegrees = radiusDegrees,
            limit = CAMERA_QUERY_LIMIT,
        ).map { camera ->
            RoadHazardCamera(
                id = "%.6f,%.6f".format(java.util.Locale.US, camera.latitude, camera.longitude),
                latitude = camera.latitude,
                longitude = camera.longitude,
                maxspeedKmh = camera.maxspeedKmh,
                wayName = camera.wayName,
                distanceMeters = MeasureGeometry.haversineMeters(
                    here,
                    GeoPoint(longitude = camera.longitude, latitude = camera.latitude),
                ),
            )
        }.sortedBy { it.distanceMeters }

        return RoadHazardContext(
            speedLimitKmh = speedLimit,
            cameras = cameras,
        )
    }

    private companion object {
        /** Metres per degree of latitude (mean Earth approximation). */
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0

        /** Never query a radius smaller than ~1 km of arc. */
        const val MIN_RADIUS_DEGREES = 0.01

        /**
         * Road snapping for the posted limit is tighter than the camera radius:
         * the limit must belong to the road under the car, not the neighbourhood.
         */
        const val SPEED_SNAP_DEGREES = 0.03

        /** Cameras within ~2 km, ranked by the SQL planar-nearest prefilter. */
        const val CAMERA_QUERY_LIMIT = 50
    }
}
