/*
 * Room entity for a single installed map pack. v0.1.1: one pack per region.
 * The actual tile/routing/search files live at `tilesPath` / `routingPath` /
 * `searchPath` on the filesystem (under `mappacks/<region>/<version>/`); this
 * table records their location and the manifest that was current at install time.
 */
package au.com.ausroads.data.pack

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import au.com.ausroads.core.model.Region
import kotlinx.datetime.Instant

@Entity(tableName = "installed_packs")
data class InstalledPackEntity(
    @PrimaryKey
    @ColumnInfo(name = "region_code")
    val region: Region,
    @ColumnInfo(name = "pack_version")
    val packVersion: String,
    @ColumnInfo(name = "installed_at")
    val installedAt: Instant,
    @ColumnInfo(name = "total_size_bytes")
    val totalSizeBytes: Long,
    @ColumnInfo(name = "tiles_path")
    val tilesPath: String,
    @ColumnInfo(name = "routing_path")
    val routingPath: String,
    @ColumnInfo(name = "search_path")
    val searchPath: String,
    @ColumnInfo(name = "manifest_json")
    val manifestJson: String,
)
