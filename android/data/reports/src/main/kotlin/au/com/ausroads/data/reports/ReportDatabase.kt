/*
 * Room database for community reports. Schema version 1.
 *
 * Reports are user data: a schema bump must NOT silently delete them. v1 has
 * no migration history yet; destructive fallback is wired in ReportsModule only
 * as a last-resort net so a corrupted/unknown on-disk schema can never
 * hard-crash the app on launch. Future bumps must add a real [Migration] here,
 * following the PinDatabase pattern.
 */
package au.com.ausroads.data.reports

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [CommunityReport::class],
    version = 1,
    // NOTE: schema export is intentionally off. Enabling it via a plain
    // `ksp { arg("room.schemaLocation", …) }` races the debug/release KSP tasks on
    // the shared schemas/ file (intermittent "EOF" in kspReleaseKotlin). Re-enable
    // via the variant-aware androidx.room Gradle plugin when adding migration tests.
    // The @ColumnInfo defaults on CommunityReport already keep fresh-install and
    // migrated schemas identical, which is the actual correctness guarantee here.
    exportSchema = false,
)
@TypeConverters(InstantConverters::class, ReportCategoryConverters::class)
abstract class ReportDatabase : RoomDatabase() {
    abstract fun communityReportDao(): CommunityReportDao

    companion object {
        const val DATABASE_NAME = "ausroads-reports.db"
    }
}
