/*
 * A community hazard report (ADR 0004 v0.5+). Persisted in Room. Pure data,
 * no behavior.
 *
 * LOCAL-ONLY foundation (docs/notes/privacy.md v0.5+): nothing here is sent
 * off-device. photoPath is reserved for a future on-device photo attach; the
 * sync column is a local state marker for a possible future backend, never a
 * queue. Reports auto-expire (expiresAt); confirm/reject counts are the local
 * confidence-scored mirror of the spec's confirm/reject buttons.
 */
package au.com.ausroads.data.reports

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(tableName = "reports")
data class CommunityReport(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: ReportCategory,
    val name: String? = null,
    // Raw lat/lon columns, matching Pin.kt — no wrapper type, no PostGIS-style
    // point encoding; clustering (if ever added) happens above this layer.
    val lat: Double,
    val lon: Double,
    @ColumnInfo(defaultValue = "")
    val note: String = "",
    @ColumnInfo(name = "photo_path")
    val photoPath: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Instant? = null,
    @ColumnInfo(name = "confirm_count", defaultValue = "0")
    val confirmCount: Int = 0,
    @ColumnInfo(name = "reject_count", defaultValue = "0")
    val rejectCount: Int = 0,
    // "LOCAL" until/unless a future sync layer exists; never leaves the device
    // from this module.
    @ColumnInfo(name = "sync_state", defaultValue = "LOCAL")
    val syncState: String = "LOCAL",
)
