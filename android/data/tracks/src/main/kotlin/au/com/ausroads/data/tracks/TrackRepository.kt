/*
 * TrackRepository — interface so the feature/UI layer never depends on Room. The single
 * production implementation is RoomTrackRepository, bound by Hilt in TracksDataModule.
 *
 * Callers pass RecordedPoint (a plain input type), not the TrackPoint Room entity, so nothing
 * upstream of this module is coupled to the persistence schema.
 */
package au.com.ausroads.data.tracks

import kotlinx.coroutines.flow.Flow

/** A track plus its ordered points, returned together for detail / export views. */
data class TrackWithPoints(
    val track: Track,
    val points: List<TrackPoint>,
)

interface TrackRepository {
    /** All tracks, newest first (started_at DESC), reactively. */
    fun observeTracks(): Flow<List<Track>>

    /**
     * Persist a recording: inserts the Track (with distance + point-count computed from
     * [points]) and its points, and returns the new track id. [startedAt] / [endedAt] default
     * to the first / last point time when not supplied.
     */
    suspend fun saveTrack(
        name: String,
        points: List<RecordedPoint>,
        startedAt: kotlinx.datetime.Instant? = null,
        endedAt: kotlinx.datetime.Instant? = null,
        notes: String = "",
    ): Long

    /** Load a track and its ordered points, or null if no such track. */
    suspend fun getTrackWithPoints(id: Long): TrackWithPoints?

    /** Delete a track; its points are removed by the ON DELETE CASCADE foreign key. */
    suspend fun deleteTrack(id: Long): Int
}
