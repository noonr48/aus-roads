package au.com.ausroads.traffic.provider.vic

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.ThemeMode
import au.com.ausroads.traffic.provider.ArcGisGeometry
import au.com.ausroads.traffic.provider.EventType
import au.com.ausroads.traffic.provider.TrafficGeometry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class VicRoadsProviderTest {

    private val fakeSettingsRepository = object : SettingsRepository {
        override val settings: Flow<Settings> = flowOf(Settings())
        override suspend fun setTheme(mode: ThemeMode) {}
        override suspend fun setShowAttributionOverlay(show: Boolean) {}
        override suspend fun setLiveTrafficEnabled(enabled: Boolean) {}
        override suspend fun setTrafficSourceEnabled(sourceId: String, enabled: Boolean) {}
        override suspend fun setTtsEnabled(enabled: Boolean) {}
        override suspend fun setCongestionOverlayEnabled(enabled: Boolean) {}
        override suspend fun setNswTrafficApiKey(key: String) {}
        override suspend fun setVicTrafficApiKey(key: String) {}
        override suspend fun setAvoidOptions(
            avoidTolls: Boolean,
            avoidUnsealed: Boolean,
            avoidFerries: Boolean,
        ) {}
    }

    private val provider = VicRoadsProvider(fakeSettingsRepository)

    @Test
    fun `regionCode is AU-VIC`() {
        assertThat(provider.regionCode).isEqualTo("AU-VIC")
    }

    @Test
    fun `displayName is VicRoads`() {
        assertThat(provider.displayName).isEqualTo("VicRoads")
    }

    @Test
    fun `supportedBbox covers Victoria`() {
        val bbox = provider.supportedBbox
        assertThat(bbox.west).isWithin(0.1).of(141.0)
        assertThat(bbox.south).isWithin(0.1).of(-39.2)
        assertThat(bbox.east).isWithin(0.1).of(150.0)
        assertThat(bbox.north).isWithin(0.1).of(-33.5)
    }

    @Test
    fun `ArcGisGeometry Point parses correctly`() {
        val geom = ArcGisGeometry(
            type = "Point",
            coordinates = Json.parseToJsonElement("[144.96, -37.81]"),
        )
        val result = geom.toTrafficGeometry()
        assertThat(result).isInstanceOf(TrafficGeometry.Point::class.java)
        val point = result as TrafficGeometry.Point
        assertThat(point.longitude).isWithin(0.01).of(144.96)
        assertThat(point.latitude).isWithin(0.01).of(-37.81)
    }

    @Test
    fun `ArcGisGeometry LineString parses correctly`() {
        val geom = ArcGisGeometry(
            type = "LineString",
            coordinates = Json.parseToJsonElement(
                "[[144.96, -37.81], [145.00, -37.85]]",
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
