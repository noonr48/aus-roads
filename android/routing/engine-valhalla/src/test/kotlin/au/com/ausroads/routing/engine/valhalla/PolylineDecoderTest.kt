package au.com.ausroads.routing.engine.valhalla

import org.junit.Test
import com.google.common.truth.Truth.assertThat

class PolylineDecoderTest {

    @Test
    fun `decodePolyline returns empty list for empty string`() {
        val points = ValhallaRoutingEngine.decodePolyline("")
        assertThat(points).isEmpty()
    }

    @Test
    fun `decodePolyline decodes origin point`() {
        // zigzag(0) = 0 => char '?' (code 63)
        val points = ValhallaRoutingEngine.decodePolyline("??")
        assertThat(points).hasSize(1)
        assertThat(points[0].latitude).isWithin(0.000001).of(0.0)
        assertThat(points[0].longitude).isWithin(0.000001).of(0.0)
    }

    @Test
    fun `decodePolyline decodes two points with positive deltas`() {
        // zigzag(1) = 2 => char 'A' (code 65)
        // "??" => (0,0), "AA" => delta (1e-6, 1e-6)
        val points = ValhallaRoutingEngine.decodePolyline("??AA")
        assertThat(points).hasSize(2)
        assertThat(points[0].latitude).isWithin(0.000001).of(0.0)
        assertThat(points[0].longitude).isWithin(0.000001).of(0.0)
        assertThat(points[1].latitude).isWithin(0.000001).of(1e-6)
        assertThat(points[1].longitude).isWithin(0.000001).of(1e-6)
    }

    @Test
    fun `decodePolyline handles negative deltas`() {
        // zigzag(-1) = 1 => char '@' (code 64)
        val points = ValhallaRoutingEngine.decodePolyline("@@")
        assertThat(points).hasSize(1)
        assertThat(points[0].latitude).isWithin(0.000001).of(-1e-6)
        assertThat(points[0].longitude).isWithin(0.000001).of(-1e-6)
    }

    @Test
    fun `decodePolyline decodes three accumulated points`() {
        // zigzag(1) = 2 => 'A', zigzag(3) = 6 => 'E'
        // Points: (0,0), (1e-6, 1e-6), (1e-6+3e-6, 1e-6+3e-6) = (4e-6, 4e-6)
        val points = ValhallaRoutingEngine.decodePolyline("??AAEE")
        assertThat(points).hasSize(3)
        assertThat(points[0].latitude).isWithin(0.000001).of(0.0)
        assertThat(points[1].latitude).isWithin(0.000001).of(1e-6)
        assertThat(points[2].latitude).isWithin(0.000001).of(4e-6)
    }
}
