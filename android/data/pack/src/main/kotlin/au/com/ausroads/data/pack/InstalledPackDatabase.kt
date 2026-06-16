/*
 * Room database for installed map pack metadata. v0.1.1: single-entity, version 1,
 * destructive migration fallback (no real users yet).
 */
package au.com.ausroads.data.pack

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [InstalledPackEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(RegionConverter::class, InstantConverters::class)
abstract class InstalledPackDatabase : RoomDatabase() {
    abstract fun installedPackDao(): InstalledPackDao

    companion object {
        const val DATABASE_NAME = "ausroads-packs.db"
    }
}
