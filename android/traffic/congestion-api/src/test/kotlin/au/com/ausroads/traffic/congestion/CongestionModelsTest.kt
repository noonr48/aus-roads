package au.com.ausroads.traffic.congestion

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.GeoPoint
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CongestionModelsTest {

    @Test
    fun `CongestionLevel has all expected values`() {
        val values = CongestionLevel.entries.map { it.name }

        assertThat(values).containsExactly("FREE", "LIGHT", "MODERATE", "HEAVY", "SEVERE")
    }

    @Test
    fun `CongestionLevel FREE has correct color hex`() {
        assertThat(CongestionLevel.FREE.colorHex).isEqualTo("#4CAF50")
    }

    @Test
    fun `CongestionLevel LIGHT has correct color hex`() {
        assertThat(CongestionLevel.LIGHT.colorHex).isEqualTo("#8BC34A")
    }

    @Test
    fun `CongestionLevel MODERATE has correct color hex`() {
        assertThat(CongestionLevel.MODERATE.colorHex).isEqualTo("#FF9800")
    }

    @Test
    fun `CongestionLevel HEAVY has correct color hex`() {
        assertThat(CongestionLevel.HEAVY.colorHex).isEqualTo("#F44336")
    }

    @Test
    fun `CongestionLevel SEVERE has correct color hex`() {
        assertThat(CongestionLevel.SEVERE.colorHex).isEqualTo("#9C27B0")
    }

    @Test
    fun `CongestionSegment fields are accessible`() {
        val segment = CongestionSegment(
            roadName = "Main North Road",
            coordinates = listOf(GeoPoint(138.6, -34.9), GeoPoint(138.61, -34.91)),
            level = CongestionLevel.MODERATE,
            speedKmh = 45.0,
            freeFlowSpeedKmh = 80.0,
        )

        assertThat(segment.roadName).isEqualTo("Main North Road")
        assertThat(segment.coordinates).hasSize(2)
        assertThat(segment.level).isEqualTo(CongestionLevel.MODERATE)
        assertThat(segment.speedKmh).isEqualTo(45.0)
        assertThat(segment.freeFlowSpeedKmh).isEqualTo(80.0)
    }

    @Test
    fun `CongestionSegment equality holds for identical instances`() {
        val coords = listOf(GeoPoint(138.6, -34.9))
        val a = CongestionSegment("South Rd", coords, CongestionLevel.HEAVY, 20.0, 60.0)
        val b = CongestionSegment("South Rd", coords, CongestionLevel.HEAVY, 20.0, 60.0)

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `CongestionSegment inequality on roadName`() {
        val coords = listOf(GeoPoint(138.6, -34.9))
        val a = CongestionSegment("South Rd", coords, CongestionLevel.HEAVY, 20.0, 60.0)
        val b = CongestionSegment("North Rd", coords, CongestionLevel.HEAVY, 20.0, 60.0)

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `CongestionSegment inequality on level`() {
        val coords = listOf(GeoPoint(138.6, -34.9))
        val a = CongestionSegment("South Rd", coords, CongestionLevel.HEAVY, 20.0, 60.0)
        val b = CongestionSegment("South Rd", coords, CongestionLevel.FREE, 20.0, 60.0)

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `CongestionProvider interface declares observeCongestion`() {
        val method = CongestionProvider::class.java.methods.singleOrNull {
            it.name == "observeCongestion"
        }

        assertThat(method).isNotNull()
        assertThat(method!!.parameterCount).isEqualTo(1)
        assertThat(method.returnType).isEqualTo(Flow::class.java)
    }

    @Test
    fun `CongestionProvider interface declares queryCongestion`() {
        val method = CongestionProvider::class.java.methods.singleOrNull {
            it.name == "queryCongestion"
        }

        assertThat(method).isNotNull()
        // Suspend functions have an extra Continuation parameter at the JVM level
        assertThat(method!!.parameterCount).isEqualTo(2)
    }

    @Test
    fun `StubCongestionProvider observeCongestion returns empty flow`() = runBlocking {
        val provider = StubCongestionProvider()

        val segments = provider.observeCongestion(
            Bbox(138.4, -35.2, 139.0, -34.6)
        ).toList()

        assertThat(segments).isEmpty()
    }

    @Test
    fun `StubCongestionProvider queryCongestion returns FREE`() = runBlocking {
        val provider = StubCongestionProvider()

        val level = provider.queryCongestion(GeoPoint(138.6, -34.9))

        assertThat(level).isEqualTo(CongestionLevel.FREE)
    }

    private class StubCongestionProvider : CongestionProvider {
        override val displayName = "Stub"
        override val costPerRequest = 0.0

        override fun observeCongestion(bbox: Bbox): Flow<List<CongestionSegment>> =
            emptyFlow()

        override suspend fun queryCongestion(point: GeoPoint): CongestionLevel =
            CongestionLevel.FREE
    }
}
