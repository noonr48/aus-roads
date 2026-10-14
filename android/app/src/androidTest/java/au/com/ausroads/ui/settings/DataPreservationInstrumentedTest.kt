package au.com.ausroads.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.data.pins.Pin
import au.com.ausroads.data.pins.PinDatabase
import au.com.ausroads.data.routes.RouteHistoryDatabase
import au.com.ausroads.data.routes.RouteHistoryEntity
import au.com.ausroads.data.routes.di.RoutesModule
import au.com.ausroads.data.tracks.Track
import au.com.ausroads.data.tracks.TrackDatabase
import au.com.ausroads.data.tracks.TrackPoint
import au.com.ausroads.data.tracks.di.TracksDataModule
import au.com.ausroads.di.AppModule
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith

/**
 * Actual Android SQLite/Room through the production providers, not another Room builder.
 * Only database paths are redirected. Each case owns a new temporary directory and never
 * opens, replaces or deletes the application's normal private databases. Physical devices
 * fail the guard BEFORE any fixture is created; these tests must never run on the Pixel.
 */
@RunWith(AndroidJUnit4::class)
class DataPreservationInstrumentedTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private val databases = mutableListOf<RoomDatabase>()

    @Before
    fun isolatedEmulatorDatabasesOnly() {
        check(Build.MODEL.startsWith("sdk_gphone") && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Data preservation fixtures are emulator-only; never run on the Pixel"
        }
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "data-preservation-").toFile()
        context = DatabaseFixtureContext(application, directory)
        val names = listOf(PinDatabase.DATABASE_NAME, RouteHistoryDatabase.DATABASE_NAME, TrackDatabase.DATABASE_NAME)
        for (name in names) {
            check(context.getDatabasePath(name).canonicalFile != application.getDatabasePath(name).canonicalFile)
        }
    }

    @After
    fun closeAndDeleteOnlyOwnedFixtures() {
        try {
            databases.forEach { it.close() }
        } finally {
            if (::directory.isInitialized) check(directory.deleteRecursively()) { "Fixture cleanup failed" }
        }
    }

    @Test
    fun freshPinDatabaseOpensThroughProductionProviderAndPreservesAllFields() = runBlocking {
        assertThat(pinFile.exists()).isFalse()
        val database = pins()
        assertOpen(database, PinDatabase.DATABASE_NAME, 2)
        val dao = AppModule.providePinDao(database)
        assertThat(dao.observeAll().first()).isEmpty()
        PIN_SENTINELS.forEach { dao.insert(it) }
        assertThat(dao.observeAll().first().sortedBy { it.id }).containsExactlyElementsIn(PIN_SENTINELS).inOrder()
        database.close()
        assertRawPins(PIN_SENTINELS)
    }

    @Test
    fun currentPinDatabaseReopensThroughProductionProviderWithoutChangingAnyRow() = runBlocking {
        seedPins()
        val before = snapshot(pinFile, "pins")
        val database = pins()
        assertOpen(database, PinDatabase.DATABASE_NAME, 2)
        assertThat(AppModule.providePinDao(database).observeAll().first().sortedBy { it.id })
            .containsExactlyElementsIn(PIN_SENTINELS).inOrder()
        database.close()
        assertThat(snapshot(pinFile, "pins")).isEqualTo(before)
        assertRawPins(PIN_SENTINELS)
    }

    @Test
    fun freshRouteDatabaseOpensThroughProductionProviderAndPreservesAllFields() = runBlocking {
        assertThat(routeFile.exists()).isFalse()
        val database = routes()
        assertOpen(database, RouteHistoryDatabase.DATABASE_NAME, 1)
        val dao = RoutesModule.provideDao(database)
        assertThat(dao.observeRecent().first()).isEmpty()
        ROUTE_SENTINELS.forEach { dao.insert(it) }
        assertThat(dao.observeRecent().first().sortedBy { it.id })
            .containsExactlyElementsIn(ROUTE_SENTINELS).inOrder()
        database.close()
        assertRawRoutes()
    }

    @Test
    fun currentRouteDatabaseReopensThroughProductionProviderWithoutChangingAnyRow() = runBlocking {
        seedRoutes()
        val before = snapshot(routeFile, "route_history")
        val database = routes()
        assertOpen(database, RouteHistoryDatabase.DATABASE_NAME, 1)
        assertThat(RoutesModule.provideDao(database).observeRecent().first().sortedBy { it.id })
            .containsExactlyElementsIn(ROUTE_SENTINELS).inOrder()
        database.close()
        assertThat(snapshot(routeFile, "route_history")).isEqualTo(before)
        assertRawRoutes()
    }

    @Test
    fun legacyV1PinsWithoutNoteOrColorMigrateAndPreserveEveryOriginalField() = runBlocking {
        legacyPins(withNoteAndColor = false)
        val original = rawRows(pinFile, "SELECT id, name, lat, lon, created_at FROM pins ORDER BY id")
        val expected = PIN_SENTINELS.map { it.copy(note = "", color = "#1B5E20") }
        val database = pins()
        assertOpen(database, PinDatabase.DATABASE_NAME, 2)
        assertThat(AppModule.providePinDao(database).observeAll().first().sortedBy { it.id })
            .containsExactlyElementsIn(expected).inOrder()
        database.close()
        assertThat(rawRows(pinFile, "SELECT id, name, lat, lon, created_at FROM pins ORDER BY id"))
            .isEqualTo(original)
        assertRawPins(expected)
        assertMigratedPinsReopen(expected)
    }

    @Test
    fun legacyV1PinsWithNoteAndColorMigrateWithoutOverwritingSentinels() = runBlocking {
        legacyPins(withNoteAndColor = true)
        val original = rawRows(pinFile, PIN_SELECT)
        val database = pins()
        assertOpen(database, PinDatabase.DATABASE_NAME, 2)
        assertThat(AppModule.providePinDao(database).observeAll().first().sortedBy { it.id })
            .containsExactlyElementsIn(PIN_SENTINELS).inOrder()
        database.close()
        assertThat(rawRows(pinFile, PIN_SELECT)).isEqualTo(original)
        assertRawPins(PIN_SENTINELS)
        assertMigratedPinsReopen(PIN_SENTINELS)
    }

    @Test
    fun unknownPinVersionRefusesAndPreservesEveryRawFieldIncludingOpaqueBytes() = runBlocking {
        seedPins()
        unknownVersion(pinFile, "pins")
        val before = snapshot(pinFile, "pins")
        // Room builders are lazy: a returned database is NOT a successful open.
        // Each repeated real open must throw the specific missing-migration error.
        repeat(2) {
            assertMissingMigration(pins(), "pins", 2)
            assertThat(snapshot(pinFile, "pins")).isEqualTo(before)
            assertRawPins(PIN_SENTINELS)
            assertOpaqueBytes(pinFile, "pins")
        }
    }

    @Test
    fun unknownRouteVersionRefusesAndPreservesEveryRawFieldIncludingOpaqueBytes() = runBlocking {
        seedRoutes()
        unknownVersion(routeFile, "route_history")
        val before = snapshot(routeFile, "route_history")
        repeat(2) {
            assertMissingMigration(routes(), "routes", 1)
            assertThat(snapshot(routeFile, "route_history")).isEqualTo(before)
            assertRawRoutes()
            assertOpaqueBytes(routeFile, "route_history")
        }
    }

    @Test
    fun unsupportedLegacyPinSchemaRefusesAndRollsBackMigrationWithoutLosingRows() {
        legacyPins(withNoteAndColor = false)
        SQLiteDatabase.openDatabase(pinFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            // An existing wrong-type column is NOT another supported legacy shape.
            database.execSQL("ALTER TABLE pins ADD COLUMN note INTEGER NOT NULL DEFAULT 37")
        }
        val before = snapshot(pinFile, "pins")
        val database = pins()
        val failure = assertThrows(IllegalStateException::class.java) { database.openHelper.writableDatabase }
        assertThat(failure).hasMessageThat().contains("pins")
        assertThat(failure).hasMessageThat().contains("Migration")
        database.close()
        // Includes user_version=1, all original fields, and absence of the new color column:
        // failed migration/validation must roll back, not stamp v2 or reset the table.
        assertThat(snapshot(pinFile, "pins")).isEqualTo(before)
        Log.i(TAG, "component=pins seam=database-open class=invalid-schema-refused")
    }

    @Test
    fun freshTracksDatabasePreservesEveryTrackAndPointField() = runBlocking {
        assertThat(trackFile.exists()).isFalse()
        seedTracks()
        assertRawTracks()
    }

    @Test
    fun currentTracksDatabaseReopensWithoutChangingTracksOrPoints() = runBlocking {
        seedTracks()
        val beforeTracks = snapshot(trackFile, "tracks")
        val beforePoints = snapshot(trackFile, "track_points")
        val database = tracks()
        assertOpen(database, TrackDatabase.DATABASE_NAME, 1)
        val dao = TracksDataModule.provideTrackDao(database)
        assertThat(dao.observeTracks().first().sortedBy { it.id })
            .containsExactlyElementsIn(TRACK_SENTINELS).inOrder()
        TRACK_SENTINELS.forEach { track ->
            assertThat(dao.getPoints(track.id)).containsExactlyElementsIn(
                POINT_SENTINELS.filter { it.trackId == track.id }.sortedBy { it.seq },
            ).inOrder()
        }
        database.close()
        assertThat(snapshot(trackFile, "tracks")).isEqualTo(beforeTracks)
        assertThat(snapshot(trackFile, "track_points")).isEqualTo(beforePoints)
        assertRawTracks()
    }

    @Test
    fun unknownTracksVersionRefusesWithoutRemovingTracksPointsOrOpaqueFields() = runBlocking {
        seedTracks()
        unknownVersion(trackFile, "tracks")
        unknownVersion(trackFile, "track_points")
        val beforeTracks = snapshot(trackFile, "tracks")
        val beforePoints = snapshot(trackFile, "track_points")
        repeat(2) {
            assertMissingMigration(tracks(), "tracks", 1)
            assertThat(snapshot(trackFile, "tracks")).isEqualTo(beforeTracks)
            assertThat(snapshot(trackFile, "track_points")).isEqualTo(beforePoints)
            assertRawTracks()
            assertOpaqueBytes(trackFile, "tracks")
            assertOpaqueBytes(trackFile, "track_points")
        }
    }

    private val pinFile: File get() = context.getDatabasePath(PinDatabase.DATABASE_NAME)
    private val routeFile: File get() = context.getDatabasePath(RouteHistoryDatabase.DATABASE_NAME)
    private val trackFile: File get() = context.getDatabasePath(TrackDatabase.DATABASE_NAME)

    private fun pins(): PinDatabase = AppModule.providePinDatabase(context).also { databases.add(it) }
    private fun routes(): RouteHistoryDatabase = RoutesModule.provideDatabase(context).also { databases.add(it) }
    private fun tracks(): TrackDatabase = TracksDataModule.provideTrackDatabase(context).also { databases.add(it) }

    private fun assertOpen(database: RoomDatabase, name: String, version: Int) {
        val opened = database.openHelper.writableDatabase
        assertThat(database.openHelper.databaseName).isEqualTo(name)
        assertThat(File(opened.path).canonicalFile).isEqualTo(context.getDatabasePath(name).canonicalFile)
        assertThat(opened.version).isEqualTo(version)
    }

    private suspend fun seedPins() {
        val database = pins()
        assertOpen(database, PinDatabase.DATABASE_NAME, 2)
        PIN_SENTINELS.forEach { AppModule.providePinDao(database).insert(it) }
        database.close()
        assertRawPins(PIN_SENTINELS)
    }

    private suspend fun seedRoutes() {
        val database = routes()
        assertOpen(database, RouteHistoryDatabase.DATABASE_NAME, 1)
        ROUTE_SENTINELS.forEach { RoutesModule.provideDao(database).insert(it) }
        database.close()
        assertRawRoutes()
    }

    private suspend fun seedTracks() {
        val database = tracks()
        assertOpen(database, TrackDatabase.DATABASE_NAME, 1)
        val dao = TracksDataModule.provideTrackDao(database)
        assertThat(dao.observeTracks().first()).isEmpty()
        TRACK_SENTINELS.forEach { dao.insertTrack(it) }
        dao.insertPoints(POINT_SENTINELS)
        assertThat(dao.observeTracks().first().sortedBy { it.id })
            .containsExactlyElementsIn(TRACK_SENTINELS).inOrder()
        TRACK_SENTINELS.forEach { track ->
            assertThat(dao.getPoints(track.id)).containsExactlyElementsIn(
                POINT_SENTINELS.filter { it.trackId == track.id }.sortedBy { it.seq },
            ).inOrder()
        }
        database.close()
        assertRawTracks()
    }

    private fun legacyPins(withNoteAndColor: Boolean) {
        SQLiteDatabase.openOrCreateDatabase(pinFile, null).use { database ->
            val extraColumns = if (withNoteAndColor) {
                ", note TEXT NOT NULL DEFAULT '', color TEXT NOT NULL DEFAULT '#1B5E20'"
            } else {
                ""
            }
            database.execSQL(
                "CREATE TABLE pins (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, created_at INTEGER NOT NULL" +
                    extraColumns + ")",
            )
            PIN_SENTINELS.forEach { pin ->
                if (withNoteAndColor) {
                    database.execSQL(
                        "INSERT INTO pins (id, name, lat, lon, note, color, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        pinFields(pin).toTypedArray(),
                    )
                } else {
                    database.execSQL(
                        "INSERT INTO pins (id, name, lat, lon, created_at) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(pin.id, pin.name, pin.lat, pin.lon, pin.createdAt.toEpochMilliseconds()),
                    )
                }
            }
            database.version = 1
        }
    }

    private suspend fun assertMigratedPinsReopen(expected: List<Pin>) {
        val before = snapshot(pinFile, "pins")
        val database = pins()
        assertOpen(database, PinDatabase.DATABASE_NAME, 2)
        assertThat(AppModule.providePinDao(database).observeAll().first().sortedBy { it.id })
            .containsExactlyElementsIn(expected).inOrder()
        database.close()
        assertThat(snapshot(pinFile, "pins")).isEqualTo(before)
    }

    private fun unknownVersion(file: File, table: String) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("ALTER TABLE $table ADD COLUMN future_payload BLOB")
            database.execSQL("UPDATE $table SET future_payload = ?", arrayOf(OPAQUE_BYTES))
            database.version = UNKNOWN_VERSION
        }
        assertOpaqueBytes(file, table)
    }

    private fun assertMissingMigration(database: RoomDatabase, component: String, currentVersion: Int) {
        val failure = assertThrows(IllegalStateException::class.java) { database.openHelper.writableDatabase }
        assertThat(failure).hasMessageThat().contains("A migration from $UNKNOWN_VERSION to $currentVersion was required")
        database.close()
        // A named refusal witness, not an empty-list/healthy-boot substitute for Room's error.
        Log.i(TAG, "component=$component seam=database-open class=unsupported-version-refused")
    }

    private fun assertRawPins(expected: List<Pin>) {
        assertThat(rawRows(pinFile, PIN_SELECT)).isEqualTo(expected.map(::pinFields))
    }

    private fun pinFields(pin: Pin): List<Any> = listOf(
        pin.id, pin.name, pin.lat, pin.lon, pin.note, pin.color, pin.createdAt.toEpochMilliseconds(),
    )

    private fun assertRawRoutes() {
        val expected = ROUTE_SENTINELS.map { route ->
            listOf(
                route.id, route.originLat, route.originLon, route.destLat, route.destLon,
                route.originName, route.destName, route.distanceMeters.toLong(), route.durationSeconds.toLong(),
                route.createdAt.toEpochMilliseconds(),
            )
        }
        assertThat(rawRows(routeFile, ROUTE_SELECT)).isEqualTo(expected)
    }

    private fun assertRawTracks() {
        assertThat(rawRows(trackFile, TRACK_SELECT)).isEqualTo(TRACK_SENTINELS.map { track ->
            listOf(
                track.id, track.name, track.startedAt.toEpochMilliseconds(),
                track.endedAt?.toEpochMilliseconds(), track.distanceMeters, track.pointCount.toLong(), track.notes,
            )
        })
        assertThat(rawRows(trackFile, POINT_SELECT)).isEqualTo(POINT_SENTINELS.map { point ->
            listOf(
                point.id, point.trackId, point.seq.toLong(), point.latitude, point.longitude,
                point.elevationMeters, point.time.toEpochMilliseconds(), point.speedMps,
            )
        })
    }

    private fun assertOpaqueBytes(file: File, table: String) {
        assertThat(rawRows(file, "SELECT future_payload FROM $table ORDER BY id"))
            .isEqualTo(List(2) { listOf(OPAQUE_BYTES.toList()) })
    }

    private data class RawSnapshot(
        val version: Int,
        val columns: List<String>,
        val rows: List<List<Any?>>,
        val schema: List<List<Any?>>,
    )

    private fun snapshot(file: File, table: String): RawSnapshot =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT * FROM $table ORDER BY id", null).use { cursor ->
                RawSnapshot(
                    version = database.version,
                    columns = cursor.columnNames.toList(),
                    rows = readRows(cursor),
                    schema = database.rawQuery(
                        "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name", null,
                    ).use(::readRows),
                )
            }
        }

    private fun rawRows(file: File, sql: String): List<List<Any?>> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery(sql, null).use(::readRows)
        }

    private fun readRows(cursor: Cursor): List<List<Any?>> = buildList {
        while (cursor.moveToNext()) {
            add(List(cursor.columnCount) { column ->
                when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                    else -> error("Unexpected SQLite field type")
                }
            })
        }
    }

    /** Redirect both SQLiteOpenHelper entry points as well as Room's path/application lookups. */
    private class DatabaseFixtureContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getDatabasePath(name: String): File {
            val requested = File(name)
            val names = setOf(PinDatabase.DATABASE_NAME, RouteHistoryDatabase.DATABASE_NAME, TrackDatabase.DATABASE_NAME)
            require(requested.name in names)
            val fixture = File(directory, requested.name)
            require(!requested.isAbsolute || requested.canonicalFile == fixture.canonicalFile)
            return fixture
        }

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = baseContext.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory)

        override fun openOrCreateDatabase(
            name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = baseContext.openOrCreateDatabase(
            getDatabasePath(name).absolutePath, mode, factory, errorHandler,
        )

        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    private companion object {
        const val TAG = "DataPreservationTest"
        const val UNKNOWN_VERSION = 99
        const val PIN_SELECT = "SELECT id, name, lat, lon, note, color, created_at FROM pins ORDER BY id"
        const val ROUTE_SELECT = "SELECT id, originLat, originLon, destLat, destLon, originName, destName, " +
            "distanceMeters, durationSeconds, createdAt FROM route_history ORDER BY id"
        const val TRACK_SELECT = "SELECT id, name, started_at, ended_at, distance_meters, point_count, notes " +
            "FROM tracks ORDER BY id"
        const val POINT_SELECT = "SELECT id, track_id, seq, latitude, longitude, elevation_meters, time, speed_mps " +
            "FROM track_points ORDER BY id"
        val OPAQUE_BYTES = byteArrayOf(0, 1, 127, -128, -1, 0)
        val PIN_SENTINELS = listOf(
            Pin(
                id = 41, name = "Sentinel creek 'α'", lat = -34.123456789, lon = 138.987654321,
                note = "Preserve every field\nincluding Unicode: 漢字", color = "#A17B3F",
                createdAt = Instant.fromEpochMilliseconds(-123_456_789L),
            ),
            Pin(
                id = 87, name = "Second sentinel — β", lat = -29.875, lon = 134.0625,
                note = "A different non-default note", color = "#123ABC",
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_123L),
            ),
        )
        val TRACK_SENTINELS = listOf(
            Track(
                id = 61, name = "Creek recording 'α'", startedAt = Instant.fromEpochMilliseconds(-234_567_890L),
                endedAt = Instant.fromEpochMilliseconds(-234_500_000L), distanceMeters = 1_234.56789,
                pointCount = 1, notes = "Preserve recording notes\n漢字",
            ),
            Track(
                id = 97, name = "Active recording — β", startedAt = Instant.fromEpochMilliseconds(1_700_000_000_123L),
                endedAt = null, distanceMeters = 98.76543, pointCount = 1, notes = "Unfinished track",
            ),
        )
        val POINT_SENTINELS = listOf(
            TrackPoint(
                id = 71, trackId = 61, seq = 4, latitude = -34.123456789, longitude = 138.987654321,
                elevationMeters = -12.345, time = Instant.fromEpochMilliseconds(-234_567_800L), speedMps = 3.125,
            ),
            TrackPoint(
                id = 109, trackId = 97, seq = 7, latitude = -29.875, longitude = 134.0625,
                elevationMeters = null, time = Instant.fromEpochMilliseconds(1_700_000_000_456L), speedMps = null,
            ),
        )
        val ROUTE_SENTINELS = listOf(
            RouteHistoryEntity(
                id = 51, originLat = -34.125, originLon = 138.9375, destLat = -29.123456789,
                destLon = 134.987654321, originName = "Origin 'α'", destName = "Destination 漢字",
                distanceMeters = 765_432, durationSeconds = 54_321,
                createdAt = Instant.fromEpochMilliseconds(-987_654_321L),
            ),
            RouteHistoryEntity(
                id = 93, originLat = -37.8125, originLon = 144.96875, destLat = -33.875,
                destLon = 151.21875, originName = "Another origin — β", destName = "Another destination\n",
                distanceMeters = 123_456, durationSeconds = 7_890,
                createdAt = Instant.fromEpochMilliseconds(1_700_000_999_987L),
            ),
        )
    }
}
