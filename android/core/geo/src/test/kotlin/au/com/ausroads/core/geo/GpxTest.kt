package au.com.ausroads.core.geo

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

class GpxTest {

    // --- round-trip ----------------------------------------------------------

    @Test
    fun roundTrip_fullDocument_preservesEverything() {
        val doc = GpxDocument(
            waypoints = listOf(
                GpxWaypoint(
                    latitude = -34.9285,
                    longitude = 138.6007,
                    elevationMeters = 50.0,
                    time = Instant.parse("2025-12-21T02:43:35Z"),
                    name = "Adelaide GPO",
                ),
            ),
            tracks = listOf(
                GpxTrack(
                    name = "Day 1",
                    segments = listOf(
                        GpxTrackSegment(
                            points = listOf(
                                GpxTrackPoint(
                                    latitude = -34.0,
                                    longitude = 138.0,
                                    elevationMeters = 100.5,
                                    time = Instant.parse("2025-12-21T03:00:00Z"),
                                ),
                                GpxTrackPoint(
                                    latitude = -34.1,
                                    longitude = 138.2,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val xml = Gpx.write(doc)
        val parsed = Gpx.parse(xml)

        assertThat(parsed).isEqualTo(doc)
    }

    @Test
    fun roundTrip_preservesNullOptionalFields() {
        // A waypoint and track point with all optionals null must come back with nulls.
        val doc = GpxDocument(
            waypoints = listOf(GpxWaypoint(latitude = 1.0, longitude = 2.0)),
            tracks = listOf(
                GpxTrack(
                    name = null,
                    segments = listOf(
                        GpxTrackSegment(
                            points = listOf(GpxTrackPoint(latitude = 3.0, longitude = 4.0)),
                        ),
                    ),
                ),
            ),
        )

        val parsed = Gpx.parse(Gpx.write(doc))

        val wpt = parsed.waypoints.single()
        assertThat(wpt.elevationMeters).isNull()
        assertThat(wpt.time).isNull()
        assertThat(wpt.name).isNull()

        val track = parsed.tracks.single()
        assertThat(track.name).isNull()
        val pt = track.segments.single().points.single()
        assertThat(pt.elevationMeters).isNull()
        assertThat(pt.time).isNull()
        assertThat(parsed).isEqualTo(doc)
    }

    @Test
    fun roundTrip_emptyDocument() {
        val doc = GpxDocument()
        val parsed = Gpx.parse(Gpx.write(doc))
        assertThat(parsed.waypoints).isEmpty()
        assertThat(parsed.tracks).isEmpty()
    }

    @Test
    fun write_usesLatLonAttributes() {
        val doc = GpxDocument(
            waypoints = listOf(GpxWaypoint(latitude = -34.9285, longitude = 138.6007)),
        )
        val xml = Gpx.write(doc)
        // Attributes are lat / lon (not latitude/longitude), per the GPX schema.
        assertThat(xml).contains("lat=\"-34.9285\"")
        assertThat(xml).contains("lon=\"138.6007\"")
        assertThat(xml).contains("<wpt")
    }

    // --- parse a hand-written minimal sample ---------------------------------

    @Test
    fun parse_minimalHardcodedSample() {
        val sample = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="-34.9285" lon="138.6007">
                <ele>50.0</ele>
                <name>Adelaide</name>
              </wpt>
              <trk>
                <name>Track A</name>
                <trkseg>
                  <trkpt lat="-34.0" lon="138.0">
                    <ele>100.0</ele>
                    <time>2025-12-21T03:00:00Z</time>
                  </trkpt>
                  <trkpt lat="-34.5" lon="138.5"/>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val doc = Gpx.parse(sample)

        assertThat(doc.waypoints).hasSize(1)
        val wpt = doc.waypoints.single()
        assertThat(wpt.latitude).isWithin(1e-9).of(-34.9285)
        assertThat(wpt.longitude).isWithin(1e-9).of(138.6007)
        assertThat(wpt.elevationMeters).isWithin(1e-9).of(50.0)
        assertThat(wpt.name).isEqualTo("Adelaide")
        assertThat(wpt.time).isNull()

        assertThat(doc.tracks).hasSize(1)
        val track = doc.tracks.single()
        assertThat(track.name).isEqualTo("Track A")
        val points = track.segments.single().points
        assertThat(points).hasSize(2)
        assertThat(points[0].elevationMeters).isWithin(1e-9).of(100.0)
        assertThat(points[0].time).isEqualTo(Instant.parse("2025-12-21T03:00:00Z"))
        assertThat(points[1].elevationMeters).isNull()
        assertThat(points[1].time).isNull()
    }

    @Test
    fun parse_ignoresUnknownTopLevelElements() {
        val sample = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>ignored</name></metadata>
              <wpt lat="1.0" lon="2.0"/>
            </gpx>
        """.trimIndent()

        val doc = Gpx.parse(sample)
        assertThat(doc.waypoints).hasSize(1)
        assertThat(doc.tracks).isEmpty()
    }

    // --- error handling ------------------------------------------------------

    @Test
    fun parse_malformedXml_throwsIllegalArgument() {
        val broken = "<gpx><wpt lat=\"1.0\" lon=\"2.0\"></gpx>" // unclosed wpt
        try {
            Gpx.parse(broken)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().isNotEmpty()
        }
    }

    @Test
    fun parse_notGpxRoot_throwsIllegalArgument() {
        val notGpx = """<?xml version="1.0"?><kml><placemark/></kml>"""
        try {
            Gpx.parse(notGpx)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("GPX")
        }
    }

    @Test
    fun parse_waypointMissingLat_throwsIllegalArgument() {
        val sample = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lon="2.0"/>
            </gpx>
        """.trimIndent()
        try {
            Gpx.parse(sample)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("lat")
        }
    }

    @Test
    fun parse_nonNumericLat_throwsIllegalArgument() {
        val sample = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="north" lon="2.0"/>
            </gpx>
        """.trimIndent()
        try {
            Gpx.parse(sample)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("lat")
        }
    }
}
