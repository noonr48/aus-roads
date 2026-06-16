package au.com.ausroads.traffic.provider.nsw

import au.com.ausroads.traffic.provider.ArcGisGeometry
import au.com.ausroads.traffic.provider.TrafficGeometry
import kotlinx.serialization.json.Json
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class ArcGisGeometryTest {

    @Test
    fun `Point geometry parses correctly`() {
        val geom = ArcGisGeometry(
            type = "Point",
            coordinates = Json.parseToJsonElement("[151.2, -33.8]"),
        )
        val result = geom.toTrafficGeometry()
        assertThat(result).isInstanceOf(TrafficGeometry.Point::class.java)
        val point = result as TrafficGeometry.Point
        assertThat(point.longitude).isWithin(0.01).of(151.2)
        assertThat(point.latitude).isWithin(0.01).of(-33.8)
    }

    @Test
    fun `LineString geometry parses correctly`() {
        val geom = ArcGisGeometry(
            type = "LineString",
            coordinates = Json.parseToJsonElement(
                "[[151.2, -33.8], [151.3, -33.7]]",
            ),
        )
        val result = geom.toTrafficGeometry()
        assertThat(result).isInstanceOf(TrafficGeometry.LineString::class.java)
        val line = result as TrafficGeometry.LineString
        assertThat(line.coordinates).hasSize(2)
    }

    @Test
    fun `unknown geometry type returns null`() {
        val geom = ArcGisGeometry(type = "Polygon", coordinates = null)
        assertThat(geom.toTrafficGeometry()).isNull()
    }
}
