/*
 * A recorded GPS track (one recording session). Persisted in Room. Pure data, no behavior.
 * Aggregate stats (distanceMeters, pointCount) are computed by RoomTrackRepository on save
 * so list views can render a summary without loading every TrackPoint.
 */
package au.com.ausroads.data.tracks

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "started_at")
    val startedAt: Instant,
    @ColumnInfo(name = "ended_at")
    val endedAt: Instant? = null,
    // NOT-NULL columns with a default carry an explicit @ColumnInfo(defaultValue = …) so a
    // future migration's SQL DEFAULT matches the fresh-install schema exactly (no Room
    // schema mismatch). Same guarantee data:pins relies on with exportSchema = false.
    @ColumnInfo(name = "distance_meters", defaultValue = "0")
    val distanceMeters: Double = 0.0,
    @ColumnInfo(name = "point_count", defaultValue = "0")
    val pointCount: Int = 0,
    @ColumnInfo(defaultValue = "")
    val notes: String = "",
)
