package au.com.ausroads.feature.trip

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

class TripShareComposerTest {

    private val eta = Instant.parse("2026-06-13T08:30:00Z")

    // Adelaide GPO area, an easy reference point.
    private val destLat = -34.92850
    private val destLon = 138.60070

    // A current position somewhere up the track.
    private val curLat = -33.86880
    private val curLon = 151.20930

    @Test
    fun compose_containsDestinationCoordinates() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
        )
        // CoordinateFormatter.decimalDegrees renders "lat, lon" to 5 dp.
        assertThat(msg).contains("-34.92850, 138.60070")
    }

    @Test
    fun compose_containsEta() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
        )
        assertThat(msg).contains("2026-06-13T08:30:00Z")
    }

    @Test
    fun compose_containsGeoLinkToCurrentPosition() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
        )
        // geo: URI is geo:lat,lon to 5 dp.
        assertThat(msg).contains("geo:-33.86880,151.20930")
    }

    @Test
    fun compose_containsHumanReadableCurrentCoords() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
        )
        assertThat(msg).contains("-33.86880, 151.20930")
    }

    @Test
    fun compose_containsCheckOnMeLine() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
        )
        assertThat(msg).contains("check on me")
    }

    @Test
    fun compose_includesNoteWhenProvided() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
            note = "White LandCruiser, Oodnadatta Track",
        )
        assertThat(msg).contains("White LandCruiser, Oodnadatta Track")
    }

    @Test
    fun compose_handlesNullCurrentPosition() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = null,
            currentLon = null,
        )
        // Destination + ETA + the safety line still present...
        assertThat(msg).contains("-34.92850, 138.60070")
        assertThat(msg).contains("2026-06-13T08:30:00Z")
        assertThat(msg).contains("check on me")
        // ...but no geo: link, since there's no current fix.
        assertThat(msg).doesNotContain("geo:")
    }

    @Test
    fun compose_handlesPartialNullCurrentPosition_treatedAsNoFix() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = null,
        )
        assertThat(msg).doesNotContain("geo:")
    }

    @Test
    fun compose_blankNoteIsOmitted() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
            note = "   ",
        )
        assertThat(msg).doesNotContain("Note:")
    }

    @Test
    fun compose_lengthWithinSmsCap_withTypicalNote() {
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
            note = "White LandCruiser, Oodnadatta Track, two adults",
        )
        assertThat(msg.length).isAtMost(TripShareComposer.MAX_MESSAGE_LENGTH)
    }

    @Test
    fun compose_lengthWithinSmsCap_withOverlongNote_isTruncated() {
        val hugeNote = "x".repeat(2000)
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
            note = hugeNote,
        )
        // Hard cap is honoured even when the note alone would blow past it...
        assertThat(msg.length).isAtMost(TripShareComposer.MAX_MESSAGE_LENGTH)
        // ...and the structural content survives the truncation.
        assertThat(msg).contains("-34.92850, 138.60070")
        assertThat(msg).contains("2026-06-13T08:30:00Z")
        assertThat(msg).contains("check on me")
    }

    @Test
    fun compose_overlongNote_keepsStructureAndShowsEllipsis() {
        val hugeNote = "y".repeat(2000)
        val msg = TripShareComposer.compose(
            destinationLat = destLat,
            destinationLon = destLon,
            etaUtc = eta,
            currentLat = curLat,
            currentLon = curLon,
            note = hugeNote,
        )
        // A note was present and had to be shortened, so the ellipsis marker appears.
        assertThat(msg).contains("…")
        assertThat(msg).contains("Note: ")
    }
}
