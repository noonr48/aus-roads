package au.com.ausroads.data.routes

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant
import kotlinx.datetime.Clock

@Entity(tableName = "route_history")
data class RouteHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originLat: Double,
    val originLon: Double,
    val destLat: Double,
    val destLon: Double,
    val originName: String = "",
    val destName: String = "",
    val distanceMeters: Int,
    val durationSeconds: Int,
    val createdAt: Instant = Clock.System.now(),
)
