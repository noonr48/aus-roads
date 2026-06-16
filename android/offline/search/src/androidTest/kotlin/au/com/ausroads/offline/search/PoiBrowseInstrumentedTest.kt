package au.com.ausroads.offline.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof that the POI-category browse / nearest queries and the
 * maxspeed lookup run against a real SQLite `search.db`-shaped file.
 *
 * Each test builds a throwaway DB in the app cache dir with the production
 * `search_meta` (+ optional `road_speed`) schema, populates a few known rows,
 * closes the writer, then opens it read-only through [FtsSearchRepository]
 * exactly as the app does at runtime. This exercises the real SQL — the JVM
 * unit tests only cover the pure [PoiCategory] mapping and the fake repo.
 *
 * Helper insert calls use positional args (the local helper signatures are
 * self-documenting) to keep every line within the 120-column limit.
 */
@RunWith(AndroidJUnit4::class)
class PoiBrowseInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val createdDbFiles = mutableListOf<File>()
    private val repo = FtsSearchRepository()

    @After
    fun tearDown() {
        repo.close()
        for (file in createdDbFiles) {
            SQLiteDatabase.deleteDatabase(file)
        }
        createdDbFiles.clear()
    }

    @Test
    fun browseByCategory_returnsOnlyMatchingClassRows() {
        val path = buildDb(withRoadSpeed = false) { db ->
            insertPoi(db, 1, "Adelaide Fuel", "amenity=fuel", ADELAIDE_LAT, ADELAIDE_LON)
            insertPoi(db, 2, "Royal Adelaide Hospital", "amenity=hospital", ADELAIDE_LAT, ADELAIDE_LON)
        }
        repo.open(path)

        val fuel = runBlocking { repo.browseByCategory(PoiCategory.FUEL, limit = 50) }

        assertThat(fuel).hasSize(1)
        assertThat(fuel.first().name).isEqualTo("Adelaide Fuel")
        assertThat(fuel.first().className).isEqualTo("amenity=fuel")
    }

    @Test
    fun nearestByCategory_returnsClosestMatchFirst() {
        val path = buildDb(withRoadSpeed = false) { db ->
            // Fuel right on the query point, a second fuel further away, and a
            // hospital that must be excluded by the class filter even though it
            // is nearer than the far fuel.
            insertPoi(db, 1, "Near Fuel", "amenity=fuel", ADELAIDE_LAT, ADELAIDE_LON)
            insertPoi(db, 2, "Far Fuel", "amenity=fuel", ADELAIDE_LAT + 0.20, ADELAIDE_LON + 0.20)
            insertPoi(db, 3, "Nearby Hospital", "amenity=hospital", ADELAIDE_LAT + 0.01, ADELAIDE_LON)
        }
        repo.open(path)

        val results = runBlocking {
            repo.nearestByCategory(
                latitude = ADELAIDE_LAT,
                longitude = ADELAIDE_LON,
                category = PoiCategory.FUEL,
                limit = 10,
                maxDistanceDegrees = 0.5,
            )
        }

        assertThat(results).hasSize(2)
        assertThat(results.first().name).isEqualTo("Near Fuel")
        assertThat(results.map { it.className }).containsExactly("amenity=fuel", "amenity=fuel")
    }

    @Test
    fun maxspeedNear_returnsNearestRoadSpeed() {
        val path = buildDb(withRoadSpeed = true) { db ->
            insertPoi(db, 1, "Adelaide Fuel", "amenity=fuel", ADELAIDE_LAT, ADELAIDE_LON)
            // Two speed points ~0.02deg apart; the 60 sits on the query point.
            insertRoadSpeed(db, 1, "Local Street", 60, ADELAIDE_LAT, ADELAIDE_LON)
            insertRoadSpeed(db, 2, "Arterial Road", 100, ADELAIDE_LAT + 0.02, ADELAIDE_LON)
        }
        repo.open(path)

        val nearLocal = runBlocking {
            repo.maxspeedNear(latitude = ADELAIDE_LAT, longitude = ADELAIDE_LON, maxDistanceDegrees = 0.05)
        }
        val nearArterial = runBlocking {
            repo.maxspeedNear(
                latitude = ADELAIDE_LAT + 0.02,
                longitude = ADELAIDE_LON,
                maxDistanceDegrees = 0.05,
            )
        }

        assertThat(nearLocal).isEqualTo(60)
        assertThat(nearArterial).isEqualTo(100)
    }

    @Test
    fun maxspeedNear_returnsNullWhenRoadSpeedTableAbsent() {
        // Old packs predate the speed layer: no road_speed table at all.
        val path = buildDb(withRoadSpeed = false) { db ->
            insertPoi(db, 1, "Adelaide Fuel", "amenity=fuel", ADELAIDE_LAT, ADELAIDE_LON)
        }
        repo.open(path)

        val speed = runBlocking {
            repo.maxspeedNear(latitude = ADELAIDE_LAT, longitude = ADELAIDE_LON, maxDistanceDegrees = 0.05)
        }

        assertThat(speed).isNull()
    }

    /**
     * Create a fresh DB file in the app cache dir with the `search_meta` table
     * (and optionally `road_speed`), run [populate] against the open writable
     * handle, then close it so [FtsSearchRepository.open] can reopen it
     * read-only. The file is tracked for deletion in [tearDown].
     */
    private fun buildDb(withRoadSpeed: Boolean, populate: (SQLiteDatabase) -> Unit): String {
        val file = File(context.cacheDir, "poi-browse-test-${System.nanoTime()}.db")
        file.delete()
        createdDbFiles.add(file)

        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                """
                CREATE TABLE search_meta (
                    id INTEGER PRIMARY KEY,
                    name TEXT,
                    kind TEXT,
                    class TEXT,
                    lat REAL,
                    lon REAL
                )
                """.trimIndent(),
            )
            if (withRoadSpeed) {
                db.execSQL(
                    """
                    CREATE TABLE road_speed (
                        id INTEGER PRIMARY KEY,
                        name TEXT,
                        maxspeed_kmh INTEGER,
                        lat REAL,
                        lon REAL
                    )
                    """.trimIndent(),
                )
            }
            populate(db)
        } finally {
            db.close()
        }
        return file.absolutePath
    }

    /** Insert one `search_meta` POI row. */
    private fun insertPoi(
        db: SQLiteDatabase,
        id: Int,
        name: String,
        className: String,
        lat: Double,
        lon: Double,
    ) {
        // execSQL bindArgs only accept String / Long / Double / byte[] — bind the
        // integer id as Long (a boxed Int throws "Cannot bind argument").
        db.execSQL(
            "INSERT INTO search_meta (id, name, kind, class, lat, lon) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(id.toLong(), name, "poi", className, lat, lon),
        )
    }

    /** Insert one `road_speed` row. */
    private fun insertRoadSpeed(
        db: SQLiteDatabase,
        id: Int,
        name: String,
        maxspeedKmh: Int,
        lat: Double,
        lon: Double,
    ) {
        // execSQL bindArgs only accept String / Long / Double / byte[] — bind the
        // integer columns as Long (a boxed Int throws "Cannot bind argument").
        db.execSQL(
            "INSERT INTO road_speed (id, name, maxspeed_kmh, lat, lon) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any>(id.toLong(), name, maxspeedKmh.toLong(), lat, lon),
        )
    }

    private companion object {
        private const val ADELAIDE_LAT = -34.92
        private const val ADELAIDE_LON = 138.60
    }
}
