/*
 * TripShareComposer — pure SMS-message builder for the offline trip-share /
 * overdue check-in feature. Produces a short, plain-text message a traveller can
 * send to a contact before heading into an area with no data coverage: where they
 * are going, when they expect to arrive, where they are right now (as a tappable
 * `geo:` link), and an instruction to raise the alarm if they do not check in.
 *
 * Pure logic only — no Android, no I/O. The actual SMS dispatch (and the platform
 * permission dance) is wired up later in the app layer; this class just formats text.
 */
package au.com.ausroads.feature.trip

import au.com.ausroads.core.geo.CoordinateFormatter
import kotlinx.datetime.Instant

/**
 * Builds the body of a "trip share / overdue check-in" SMS.
 *
 * The output is deliberately compact so it fits inside roughly two SMS segments
 * (the GSM 7-bit limit is 160 chars/segment); [compose] caps the result at
 * [MAX_MESSAGE_LENGTH] characters, trimming the optional free-text note first if
 * the whole message would otherwise overflow.
 */
object TripShareComposer {

    /**
     * Hard ceiling on the produced message length, in characters (~2 SMS segments).
     * Messages are kept at or below this; the note is the only part that is shortened
     * to honour the limit (the structural who/where/ETA lines are always preserved).
     */
    const val MAX_MESSAGE_LENGTH: Int = 320

    /** Marker appended when the note had to be truncated to fit the length cap. */
    private const val ELLIPSIS = "…"

    /**
     * Compose the share message.
     *
     * @param destinationLat destination latitude in degrees.
     * @param destinationLon destination longitude in degrees.
     * @param etaUtc the expected arrival time (UTC instant); rendered with a trailing
     *   `Z` so the recipient sees it is an absolute UTC time, not a local guess.
     * @param currentLat current latitude in degrees, or `null` if a fix is unavailable.
     * @param currentLon current longitude in degrees, or `null` if a fix is unavailable.
     *   Both [currentLat] and [currentLon] must be non-null for the current-position
     *   line (and its `geo:` link) to be included.
     * @param note optional free-text note appended at the end (e.g. vehicle/route detail).
     * @return a plain-text message no longer than [MAX_MESSAGE_LENGTH] characters.
     */
    fun compose(
        destinationLat: Double,
        destinationLon: Double,
        etaUtc: Instant,
        currentLat: Double?,
        currentLon: Double?,
        note: String? = null,
    ): String {
        val destText = CoordinateFormatter.decimalDegrees(destinationLat, destinationLon)
        val etaText = etaUtc.toString() // ISO-8601, e.g. 2026-06-13T08:30:00Z

        val lines = mutableListOf<String>()
        lines += "Heading to $destText."
        lines += "ETA $etaText."

        if (currentLat != null && currentLon != null) {
            val currentText = CoordinateFormatter.decimalDegrees(currentLat, currentLon)
            lines += "I'm at $currentText now ${geoUri(currentLat, currentLon)}."
        }

        lines += "If I'm not there by ETA, check on me."

        val trimmedNote = note?.trim()
        if (!trimmedNote.isNullOrEmpty()) {
            lines += "Note: $trimmedNote"
        }

        val full = lines.joinToString("\n")
        if (full.length <= MAX_MESSAGE_LENGTH) return full

        // Too long: the only discretionary content is the note, so shorten/drop it.
        return clampWithNote(lines, trimmedNote)
    }

    /**
     * Rebuild the message with the note shortened (or removed) so the whole thing fits
     * within [MAX_MESSAGE_LENGTH]. The structural lines are never dropped; if even they
     * exceed the cap (pathological coordinates), the result is hard-truncated as a last
     * resort so the contract "<= MAX_MESSAGE_LENGTH" always holds.
     */
    private fun clampWithNote(lines: List<String>, trimmedNote: String?): String {
        val hasNote = !trimmedNote.isNullOrEmpty()
        val structural = if (hasNote) lines.dropLast(1) else lines
        val structuralText = structural.joinToString("\n")

        if (!hasNote) {
            return structuralText.take(MAX_MESSAGE_LENGTH)
        }

        // Budget left for the note line after the structural lines + the joining newline.
        val prefix = "$structuralText\nNote: "
        val budget = MAX_MESSAGE_LENGTH - prefix.length
        if (budget <= ELLIPSIS.length) {
            // No meaningful room for a note; keep just the structural part.
            return structuralText.take(MAX_MESSAGE_LENGTH)
        }
        val keep = budget - ELLIPSIS.length
        val shortenedNote = trimmedNote.take(keep).trimEnd() + ELLIPSIS
        return "$prefix$shortenedNote"
    }

    /**
     * RFC 5870 `geo:` URI for a coordinate, e.g. `geo:-34.92850,138.60070`. Most phones
     * make this tappable and hand it to a maps app. Order is `latitude,longitude`.
     * Coordinates are rendered to 5 dp (~1 m) without locale-dependent separators.
     */
    private fun geoUri(latitude: Double, longitude: Double): String =
        "geo:${fixed5(latitude)},${fixed5(longitude)}"

    /** Fixed 5-dp rendering with a '.' decimal separator, locale-independent. */
    private fun fixed5(value: Double): String {
        val scaled = Math.round(value * 100_000.0)
        val sign = if (scaled < 0) "-" else ""
        val abs = kotlin.math.abs(scaled)
        val whole = abs / 100_000L
        val frac = (abs % 100_000L).toString().padStart(5, '0')
        return "$sign$whole.$frac"
    }
}
