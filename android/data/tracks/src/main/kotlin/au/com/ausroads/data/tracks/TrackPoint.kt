/*
 * One breadcrumb sample within a Track. Persisted in Room. Pure data, no behavior.
 *
 * Raw GPS lat/lon are stored as plain Doubles (NOT a core:geo GeoPoint) to keep this
 * persistence module free of a cross-module coupling in Wave A. Note GeoPoint elsewhere
 * is (longitude, latitude); here the columns are named `latitude`/`longitude` so the
 * axis is unambiguous on disk.
 *
 * trackId is a CASCADE foreign key onto Track.id, and is indexed so getPoints(trackId)
 * and the delete-cascade are not full-table scans.
 */
package au.com.ausroads.data.tracks

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(
    tableName = "track_points",
    indices = [Index("track_id")],
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    val seq: Int,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "elevation_meters")
    val elevationMeters: Double? = null,
    val time: Instant,
    @ColumnInfo(name = "speed_mps")
    val speedMps: Double? = null,
)
