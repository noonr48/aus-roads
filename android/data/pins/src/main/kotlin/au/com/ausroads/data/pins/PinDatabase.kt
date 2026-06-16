/*
 * Room database for pins. Schema version 2.
 *
 * Pins are user data: a schema bump must NOT silently delete them. [MIGRATIONS]
 * carries the real migration path; destructive fallback is wired in AppModule only
 * as a last-resort net so a corrupted/unknown on-disk schema can never hard-crash
 * the app on launch.
 */
package au.com.ausroads.data.pins

import android.database.sqlite.SQLiteException
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Pin::class],
    version = 2,
    // NOTE: schema export is intentionally off. Enabling it via a plain
    // `ksp { arg("room.schemaLocation", …) }` races the debug/release KSP tasks on
    // the shared schemas/ file (intermittent "EOF" in kspReleaseKotlin). Re-enable
    // via the variant-aware androidx.room Gradle plugin when adding migration tests.
    // The @ColumnInfo defaults on Pin already keep fresh-install and migrated
    // schemas identical, which is the actual correctness guarantee here.
    exportSchema = false,
)
@TypeConverters(InstantConverters::class)
abstract class PinDatabase : RoomDatabase() {
    abstract fun pinDao(): PinDao

    companion object {
        const val DATABASE_NAME = "ausroads-pins.db"

        /**
         * v1 -> v2 added the `note` and `color` columns. The original v1 schema was
         * never exported, so each ALTER is guarded: if a column already exists on a
         * given install the duplicate-column error is swallowed, keeping the
         * migration idempotent and data-preserving across both possible v1 shapes.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "ALTER TABLE pins ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing(db, "ALTER TABLE pins ADD COLUMN color TEXT NOT NULL DEFAULT '#1B5E20'")
            }
        }

        /**
         * Execute an ADD COLUMN that is a no-op only if the column already exists
         * (some early v1 installs may already carry it). Any *other* SQLite error
         * is rethrown so a genuinely failed migration is never silently marked as
         * succeeded — which would strand the DB at v2 with missing columns and
         * crash later reads.
         */
        private fun addColumnIfMissing(db: SupportSQLiteDatabase, sql: String) {
            try {
                db.execSQL(sql)
            } catch (e: SQLiteException) {
                if (e.message?.contains("duplicate column", ignoreCase = true) != true) throw e
            }
        }
    }
}
