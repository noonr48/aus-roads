/*
 * Room database for recorded tracks. Schema version 1 (no migration needed yet).
 *
 * Tracks are user data. When a future schema bump lands, the @ColumnInfo defaults on Track
 * already keep fresh-install and migrated schemas identical (the same correctness guarantee
 * data:pins relies on). Foreign-key constraint enforcement is enabled by Room automatically
 * because TrackPoint declares a ForeignKey, so deleting a Track cascades to its points.
 */
package au.com.ausroads.data.tracks

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Track::class, TrackPoint::class],
    version = 1,
    // NOTE: schema export is intentionally off. Enabling it via a plain
    // `ksp { arg("room.schemaLocation", …) }` races the debug/release KSP tasks on the
    // shared schemas/ file (intermittent "EOF" in kspReleaseKotlin). Re-enable via the
    // variant-aware androidx.room Gradle plugin when adding migration tests. The
    // @ColumnInfo defaults on Track already keep fresh-install and migrated schemas
    // identical, which is the actual correctness guarantee here.
    exportSchema = false,
)
@TypeConverters(InstantConverters::class)
abstract class TrackDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val DATABASE_NAME = "ausroads-tracks.db"
    }
}
