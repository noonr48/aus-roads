/*
 * A user-created pin. Persisted in Room. Pure data, no behavior.
 * Fields match the spec: id, name, lat, lon, note, createdAt.
 */
package au.com.ausroads.data.pins

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(tableName = "pins")
data class Pin(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(defaultValue = "")
    val note: String = "",
    // Canonical pin default — matches PinListViewModel.addPinAt and the
    // MIGRATION_1_2 backfill. Deliberately a non-traffic hue (see PinsScreen
    // PIN_COLORS) so a pin is never confused with a traffic-severity marker.
    // The @ColumnInfo defaultValue mirrors the migration's SQL DEFAULT so the
    // fresh-install and migrated schemas are identical (no Room schema mismatch).
    @ColumnInfo(defaultValue = "#1B5E20")
    val color: String = "#1B5E20",
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
