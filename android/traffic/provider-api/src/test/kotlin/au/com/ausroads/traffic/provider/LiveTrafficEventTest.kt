/*
 * LiveTrafficEvent.primaryKey is the dedup key for the traffic pipeline. It must be
 * stable across geometry changes (a position correction shouldn't create a new event)
 * and must use the canonical "source:id" separator. Lock that contract here.
 */
package au.com.ausroads.traffic.provider

import au.com.ausroads.core.model.Region
import au.com.ausroads.core.model.RoutingEffect
import au.com.ausroads.traffic.provider.TrafficGeometry.Point
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveTrafficEventTest {

    @Test
    fun `primaryKey uses source colon id format`() {
        val event = sampleEvent(
            source = "Traffic SA",
            id = "12345",
            geometry = Point(138.6, -34.9),
        )

        assertThat(event.primaryKey).isEqualTo("Traffic SA:12345")
    }

    @Test
    fun `primaryKey is stable when geometry changes (dedup-safe)`() {
        val original = sampleEvent(
            source = "Traffic SA",
            id = "12345",
            geometry = Point(138.6, -34.9),
        )
        val corrected = sampleEvent(
            source = "Traffic SA",
            id = "12345",
            geometry = Point(138.6001, -34.9001),
        )

        assertThat(original.primaryKey).isEqualTo(corrected.primaryKey)
    }

    @Test
    fun `primaryKey differs when source differs`() {
        val sa = sampleEvent(source = "Traffic SA", id = "12345")
        val nsw = sampleEvent(source = "Live Traffic NSW", id = "12345")

        assertThat(sa.primaryKey).isNotEqualTo(nsw.primaryKey)
    }

    @Test
    fun `primaryKey differs when id differs`() {
        val a = sampleEvent(source = "Traffic SA", id = "1")
        val b = sampleEvent(source = "Traffic SA", id = "2")

        assertThat(a.primaryKey).isNotEqualTo(b.primaryKey)
    }

    @Test
    fun `default routingEffect is None`() {
        val event = sampleEvent()

        assertThat(event.routingEffect).isEqualTo(RoutingEffect.None)
    }

    @Test
    fun `default confidence is 1`() {
        val event = sampleEvent()

        assertThat(event.confidence).isEqualTo(1.0)
    }

    private fun sampleEvent(
        source: String = "Traffic SA",
        id: String = "1",
        geometry: TrafficGeometry = Point(138.6, -34.9),
    ): LiveTrafficEvent = LiveTrafficEvent(
        id = id,
        source = source,
        sourceType = SourceType.OFFICIAL,
        region = Region.AU_SA,
        type = EventType.ROADWORKS,
        severity = Severity.MEDIUM,
        description = "Sample",
        geometry = geometry,
        startTime = null,
        endTime = null,
        attributes = emptyMap(),
        attribution = "Test",
    )
}
