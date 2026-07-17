package au.com.ausroads.ui.map

import au.com.ausroads.core.model.GeoPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteOriginPolicyTest {
    private val destination = point(latitude = -34.9285, longitude = 138.6007)
    private val currentCamera = point(latitude = -34.9200, longitude = 138.6100)
    private val preFocusCamera = point(latitude = -34.9350, longitude = 138.5800)

    @Test
    fun `live location wins over every camera fallback`() {
        val liveLocation = point(latitude = -34.9500, longitude = 138.6200)

        val origin = chooseRouteOrigin(
            liveLocation = liveLocation,
            currentCamera = currentCamera,
            preFocusCamera = preFocusCamera,
            destination = destination,
            cameraFocusedOnDestination = true,
        )

        assertThat(origin).isEqualTo(liveLocation)
    }

    @Test
    fun `focused result uses camera position captured before focus when GPS is absent`() {
        val origin = chooseRouteOrigin(
            liveLocation = null,
            currentCamera = destination,
            preFocusCamera = preFocusCamera,
            destination = destination,
            cameraFocusedOnDestination = true,
        )

        assertThat(origin).isEqualTo(preFocusCamera)
    }

    @Test
    fun `ordinary destination uses current camera when GPS is absent`() {
        val origin = chooseRouteOrigin(
            liveLocation = null,
            currentCamera = currentCamera,
            preFocusCamera = preFocusCamera,
            destination = destination,
            cameraFocusedOnDestination = false,
        )

        assertThat(origin).isEqualTo(currentCamera)
    }

    @Test
    fun `effectively zero length origin is rejected`() {
        val originWithinOneMetre = point(latitude = -34.928495, longitude = 138.6007)

        val origin = chooseRouteOrigin(
            liveLocation = null,
            currentCamera = originWithinOneMetre,
            preFocusCamera = null,
            destination = destination,
            cameraFocusedOnDestination = false,
        )

        assertThat(origin).isNull()
    }

    private fun point(latitude: Double, longitude: Double): GeoPoint =
        GeoPoint(longitude = longitude, latitude = latitude)
}
