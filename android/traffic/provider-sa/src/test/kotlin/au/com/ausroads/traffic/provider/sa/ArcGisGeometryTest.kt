package au.com.ausroads.traffic.provider.sa

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
            coordinates = Json.parseToJsonElement("[138.6, -34.9]"),
        )
        val result = geom.toTrafficGeometry()
        assertThat(result).isInstanceOf(TrafficGeometry.Point::class.java)
        val point = result as TrafficGeometry.Point
        assertThat(point.longitude).isWithin(0.01).of(138.6)
        assertThat(point.latitude).isWithin(0.01).of(-34.9)
    }

    @Test
    fun `LineString geometry parses correctly`() {
        val geom = ArcGisGeometry(
            type = "LineString",
            coordinates = Json.parseToJsonElement(
                "[[138.6, -34.9], [138.7, -34.8]]",
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
