package au.com.ausroads.traffic.provider.nsw

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.ThemeMode
import au.com.ausroads.traffic.provider.ArcGisGeometry
import au.com.ausroads.traffic.provider.EventType
import au.com.ausroads.traffic.provider.Severity
import au.com.ausroads.traffic.provider.TrafficGeometry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class NswLiveTrafficProviderTest {

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

    private val provider = NswLiveTrafficProvider(fakeSettingsRepository)

    @Test
    fun `regionCode is AU-NSW`() {
        assertThat(provider.regionCode).isEqualTo("AU-NSW")
    }

    @Test
    fun `displayName is NSW Live Traffic`() {
        assertThat(provider.displayName).isEqualTo("NSW Live Traffic")
    }

    @Test
    fun `supportedBbox covers NSW`() {
        val bbox = provider.supportedBbox
        assertThat(bbox.west).isAtMost(141.0)
        assertThat(bbox.east).isAtLeast(153.0)
        assertThat(bbox.south).isAtMost(-37.0)
        assertThat(bbox.north).isAtLeast(-28.0)
    }

    @Test
    fun `Point geometry parses correctly`() {
        val geom = ArcGisGeometry(
            type = "Point",
            coordinates = Json.parseToJsonElement("[151.2093, -33.8688]"),
        )
        val result = geom.toTrafficGeometry()
        assertThat(result).isInstanceOf(TrafficGeometry.Point::class.java)
        val point = result as TrafficGeometry.Point
        assertThat(point.longitude).isWithin(0.01).of(151.2093)
        assertThat(point.latitude).isWithin(0.01).of(-33.8688)
    }

    @Test
    fun `LineString geometry parses correctly`() {
        val geom = ArcGisGeometry(
            type = "LineString",
            coordinates = Json.parseToJsonElement(
                "[[151.2093, -33.8688], [151.2100, -33.8690]]",
            ),
        )
        val result = geom.toTrafficGeometry()
        assertThat(result).isInstanceOf(TrafficGeometry.LineString::class.java)
        val line = result as TrafficGeometry.LineString
        assertThat(line.coordinates).hasSize(2)
    }
}
