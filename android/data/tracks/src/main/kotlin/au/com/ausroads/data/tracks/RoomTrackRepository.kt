/*
 * Room-backed TrackRepository. Bound by Hilt in TracksDataModule.
 *
 * On save it computes the track's distance + point-count from the recorded points (via the
 * pure TrackStats helper) so list views never have to load every point, then inserts the
 * Track and maps each RecordedPoint -> TrackPoint (stamping the new trackId and a 0-based seq
 * in capture order). Mirrors data:pins RoomPinRepository: @Singleton + @Inject constructor,
 * thin delegation over the DAO.
 */
package au.com.ausroads.data.tracks

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Singleton
class RoomTrackRepository @Inject constructor(
    private val dao: TrackDao,
) : TrackRepository {

    override fun observeTracks(): Flow<List<Track>> = dao.observeTracks()

    override suspend fun saveTrack(
        name: String,
        points: List<RecordedPoint>,
        startedAt: Instant?,
        endedAt: Instant?,
        notes: String,
    ): Long {
        val stats = TrackStats.compute(points)
        val track = Track(
            name = name,
            startedAt = startedAt ?: points.firstOrNull()?.time ?: Instant.fromEpochMilliseconds(0L),
            endedAt = endedAt ?: points.lastOrNull()?.time,
            distanceMeters = stats.distanceMeters,
            pointCount = stats.pointCount,
            notes = notes,
        )
        val trackId = dao.insertTrack(track)
        if (points.isNotEmpty()) {
            val entities = points.mapIndexed { index, p ->
                TrackPoint(
                    trackId = trackId,
                    seq = index,
                    latitude = p.latitude,
                    longitude = p.longitude,
                    elevationMeters = p.elevationMeters,
                    time = p.time,
                    speedMps = p.speedMps,
                )
            }
            dao.insertPoints(entities)
        }
        return trackId
    }

    override suspend fun getTrackWithPoints(id: Long): TrackWithPoints? {
        val track = dao.getTrack(id) ?: return null
        return TrackWithPoints(track = track, points = dao.getPoints(id))
    }

    override suspend fun deleteTrack(id: Long) = dao.deleteTrack(id)
}
