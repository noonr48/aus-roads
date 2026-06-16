package au.com.ausroads.feature.navigation

import au.com.ausroads.core.model.GeoPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavigationGeometryTest {

    // GeoPoint is (longitude, latitude); build by name so lat/lon can't be swapped.
    private fun p(lat: Double, lon: Double) = GeoPoint(longitude = lon, latitude = lat)

    private val adelaide = p(lat = -34.9285, lon = 138.6007)
    private val glenelg = p(lat = -34.9803, lon = 138.5089)

    @Test
    fun `haversine is zero for the same point`() {
        assertThat(NavigationGeometry.haversineMeters(adelaide, adelaide)).isEqualTo(0.0)
    }

    @Test
    fun `haversine matches the Adelaide to Glenelg distance`() {
        // ~10.16 km great-circle on the spherical model.
        assertThat(NavigationGeometry.haversineMeters(adelaide, glenelg)).isWithin(50.0).of(10_157.0)
    }

    @Test
    fun `nearest route point picks the closest vertex`() {
        val route = listOf(p(-34.90, 138.60), p(-34.92, 138.60), p(-34.94, 138.60))
        val near = NavigationGeometry.nearestRoutePoint(route, p(-34.921, 138.601))
        assertThat(near).isNotNull()
        assertThat(near!!.index).isEqualTo(1)
        assertThat(near.distanceMeters).isLessThan(250.0)
    }

    @Test
    fun `nearest route point is null for an empty route`() {
        assertThat(NavigationGeometry.nearestRoutePoint(emptyList(), adelaide)).isNull()
    }

    @Test
    fun `remaining distance equals the final leg when standing on the middle vertex`() {
        val route = listOf(p(-34.90, 138.60), p(-34.91, 138.60), p(-34.92, 138.60))
        val finalLeg = NavigationGeometry.haversineMeters(route[1], route[2])
        assertThat(NavigationGeometry.remainingDistanceMeters(route, route[1])).isWithin(1.0).of(finalLeg)
    }

    @Test
    fun `distance from index sums the tail of the route`() {
        val route = listOf(p(-34.90, 138.60), p(-34.91, 138.60), p(-34.92, 138.60))
        val expected = NavigationGeometry.haversineMeters(route[1], route[2])
        assertThat(NavigationGeometry.distanceFromIndex(route, 1)).isWithin(1.0).of(expected)
    }
}
