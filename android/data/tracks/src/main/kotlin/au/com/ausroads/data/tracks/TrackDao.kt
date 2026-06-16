/*
 * Room DAO for the tracks + track_points tables. Simple CRUD with a Flow for reactive reads.
 *
 * Mirrors data:pins PinDao conventions: @Insert returns Long (rowid); mutating @Update /
 * DELETE queries return Int (rows affected) rather than Unit, because Room + KSP 2.x emit
 * the "unexpected jvm signature V" error on a suspend fun that returns Unit.
 */
package au.com.ausroads.data.tracks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrack(track: Track): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPoints(points: List<TrackPoint>)

    @Query("SELECT * FROM tracks ORDER BY started_at DESC")
    fun observeTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrack(id: Long): Track?

    @Query("SELECT * FROM track_points WHERE track_id = :trackId ORDER BY seq")
    suspend fun getPoints(trackId: Long): List<TrackPoint>

    @Update
    suspend fun updateTrack(track: Track): Int

    // Delete by id; track_points rows are removed by the ON DELETE CASCADE foreign key.
    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: Long): Int

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int
}
