/*
 * Caller-facing input type for a single recorded GPS sample. Lets the recorder / feature
 * layer hand the repository a list of points WITHOUT depending on the Room TrackPoint entity.
 * RoomTrackRepository maps RecordedPoint -> TrackPoint (assigning trackId + seq) on save.
 */
package au.com.ausroads.data.tracks

import kotlinx.datetime.Instant

data class RecordedPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val time: Instant,
    val speedMps: Double? = null,
)
