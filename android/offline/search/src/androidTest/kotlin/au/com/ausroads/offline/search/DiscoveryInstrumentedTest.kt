package au.com.ausroads.offline.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android system SQLite, never a fake SQL result or owner application data. */
@RunWith(AndroidJUnit4::class)
class DiscoveryInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()
    private val repository = FtsSearchRepository()

    @After
    fun cleanup() {
        repository.close()
        files.forEach(SQLiteDatabase::deleteDatabase)
    }

    @Test
    fun keywordsMatchActualClassesAndLiteralWildcards(): Unit = runBlocking {
        val file = fixture { db ->
            insert(db, "Plain venue", "amenity=cafe")
            insert(db, "100% coffee", "amenity=cafe")
            insert(db, "A_B", "amenity=cafe")
            insert(db, "A\\B", "amenity=cafe")
            insert(db, "O'Brien", "amenity=cafe")
            insert(db, "Not a cafe", "amenity=restaurant")
        }
        repository.open(file.absolutePath)
        assertThat(repository.discover(request("coffee")).map { it.className }.toSet())
            .containsExactly("amenity=cafe")
        assertThat(repository.discover(request("%", ExploreCategory.COFFEE)).map { it.name })
            .containsExactly("100% coffee")
        assertThat(repository.discover(request("_", ExploreCategory.COFFEE)).map { it.name })
            .containsExactly("A_B")
        assertThat(repository.discover(request("\\", ExploreCategory.COFFEE)).map { it.name })
            .containsExactly("A\\B")
        assertThat(repository.discover(request("O'Brien", ExploreCategory.COFFEE)).map { it.name })
            .containsExactly("O'Brien")
    }

    @Test
    fun denseCoffeeCannotTruncateSparseNatureBeforeRanking(): Unit = runBlocking {
        val file = fixture { db ->
            repeat(70) { insert(db, "Coffee $it", "amenity=cafe") }
            insert(db, "A park", "park", kind = "park")
            insert(db, "Full-tag park", "leisure=park", kind = "park")
        }
        repository.open(file.absolutePath)
        val candidates = repository.discover(request())
        assertThat(candidates.count { it.className == "amenity=cafe" }).isEqualTo(60)
        assertThat(candidates.map { it.name }).containsAtLeast("A park", "Full-tag park")
    }

    @Test
    fun datelineNearerVenueSurvivesCandidateQuota(): Unit = runBlocking {
        val file = fixture { db ->
            repeat(60) { insert(db, "Decoy $it", "amenity=cafe", 0.0, 179.80) }
            insert(db, "Across dateline", "amenity=cafe", 0.0, -179.99)
        }
        repository.open(file.absolutePath)
        val candidates =
            repository.discover(
                DiscoveryRequest(0.0, 179.95, 20, category = ExploreCategory.COFFEE)
            )
        assertThat(candidates.first().name).isEqualTo("Across dateline")
        assertThat(candidates).hasSize(60)
    }

    @Test
    fun unavailableAndMalformedCatalogAreNotSuccessfulEmptyLists() {
        repository.close()
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.UNAVAILABLE)
        assertThrows(DiscoveryUnavailableException::class.java) {
            runBlocking { repository.search("coffee") }
        }
        val broken = File(context.cacheDir, "broken-${System.nanoTime()}.db").also { files += it }
        broken.writeText("not a SQLite database")
        repository.open(broken.absolutePath)
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.FAILED)
        assertThrows(DiscoveryUnavailableException::class.java) {
            runBlocking { repository.discover(request()) }
        }
    }

    @Test
    fun replacementAndConcurrentQueriesUseCompleteValidCatalogs(): Unit = runBlocking {
        val first = fixture { insert(it, "FIRST", "amenity=cafe") }
        val second = fixture { insert(it, "SECOND", "amenity=cafe") }
        repository.open(first.absolutePath)
        val generation = repository.generation.value
        val querying =
            async(Dispatchers.Default) {
                repeat(20) {
                    val result = repository.discover(request("coffee"))
                    assertThat(result.single().name).isAnyOf("FIRST", "SECOND")
                }
            }
        repeat(10) { repository.open(if (it % 2 == 0) second.absolutePath else first.absolutePath) }
        querying.await()
        repository.open(second.absolutePath)
        assertThat(repository.generation.value).isGreaterThan(generation)
        assertThat(repository.search("SECOND").single().name).isEqualTo("SECOND")
    }

    @Test
    fun malformedRowsNeverPublishReadyAndValidReplacementRecovers(): Unit = runBlocking {
        val healthy = rawFixture(listOf(row()))
        val malformed = listOf(
            row(name = null), row(name = ""), row(name = " \t\n"),
            row(name = 42L), row(name = byteArrayOf(1)),
            row(kind = null), row(kind = 1L), row(kind = ""), row(kind = "unknown"),
            row(tag = ""), row(tag = " \t"), row(tag = 42L), row(tag = byteArrayOf(1)),
            row(tag = "amenity="), row(tag = "amenity=  "), row(tag = "cafe"),
            row(tag = "unknown=cafe"), row(kind = "park", tag = "amenity=cafe"),
            row(kind = "water", tag = "park"), row(kind = "suburb", tag = "amenity=cafe"),
            row(kind = "road", tag = "amenity=cafe"),
            row(lat = null), row(lon = null), row(lat = "not-a-number"),
            row(lon = "138.62"), row(lat = byteArrayOf(1)), row(lon = byteArrayOf(1)),
            row(lat = Double.NaN), row(lat = Double.POSITIVE_INFINITY),
            row(lon = Double.NEGATIVE_INFINITY), row(lat = -90.01), row(lat = 90.01),
            row(lon = -180.01), row(lon = 180.01),
        )
        val observed = mutableListOf<DiscoveryAvailability>()
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            repository.availability.collect { observed += it }
        }
        try {
            malformed.forEach { invalid ->
                repository.open(healthy.path)
                assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
                val generation = repository.generation.value
                // The first row is healthy: probing LIMIT 1 cannot detect these controls.
                val broken = rawFixture(listOf(row(), invalid))
                observed.clear()
                repository.open(broken.path)
                assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.FAILED)
                assertThat(observed).doesNotContain(DiscoveryAvailability.READY)
                assertThat(repository.generation.value).isEqualTo(generation + 1L)
                assertThrows(DiscoveryUnavailableException::class.java) {
                    runBlocking { repository.search("Fixture") }
                }
                assertThrows(DiscoveryUnavailableException::class.java) {
                    runBlocking { repository.discover(request()) }
                }
                repository.open(healthy.path)
                assertThat(repository.search("Fixture").single().name).isEqualTo("Fixture")
                assertThat(repository.generation.value).isEqualTo(generation + 2L)
            }
            repository.open(rawFixture(emptyList()).path)
            assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.FAILED)
        } finally {
            watcher.cancelAndJoin()
        }
    }

    @Test
    fun healthyLegacyKindsNullableClassesAndOptionalRoadLayersRemainUsable(): Unit = runBlocking {
        val rows = listOf(
            row(), row(tag = null), row(tag = "amenity=Natural Rock Formation - views"),
            row(tag = "shop=clothes;florist;houseware"), row(tag = "place=city"),
            row(kind = "park", tag = "park"), row(kind = "park", tag = "leisure=park"),
            row(kind = "water", tag = "water"), row(kind = "suburb", tag = "town"),
            row(kind = "road", tag = "residential"), row(lat = -90.0, lon = -180.0),
            row(lat = 90.0, lon = 180.0), row(lat = 0L, lon = 0L),
        )
        val file = rawFixture(rows)
        repository.open(file.path)
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        assertThat(repository.search("Fixture", limit = 100)).hasSize(rows.size)
        val parkResults = listOf("park", "leisure=park").map { tag ->
            SearchResult("Fixture", "park", tag, -34.92, 138.62)
        }
        listOf(
            request("parks"),
            request(category = ExploreCategory.NATURE),
            request("parks", ExploreCategory.NATURE),
        ).forEach { parkRequest ->
            assertThat(repository.discover(parkRequest)).containsExactlyElementsIn(parkResults)
        }
        assertThat(repository.discover(request("coffee", ExploreCategory.NATURE))).isEmpty()
        assertThat(repository.maxspeedNear(-34.92, 138.62)).isNull()
        assertThat(repository.camerasNear(-34.92, 138.62)).isEmpty()
        repository.close()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE road_speed(lat REAL,lon REAL,maxspeed_kmh INTEGER)")
            db.execSQL("INSERT INTO road_speed VALUES (-34.92,138.62,60)")
            db.execSQL("CREATE TABLE road_cameras(lat REAL,lon REAL,maxspeed_kmh INTEGER,way_name TEXT)")
            db.execSQL("INSERT INTO road_cameras VALUES (-34.92,138.62,NULL,NULL)")
        }
        repository.open(file.path)
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        assertThat(repository.maxspeedNear(-34.92, 138.62)).isEqualTo(60)
        val camera = repository.camerasNear(-34.92, 138.62).single()
        assertThat(camera.latitude).isEqualTo(-34.92)
        assertThat(camera.longitude).isEqualTo(138.62)
        assertThat(camera.maxspeedKmh).isNull()
        assertThat(camera.wayName).isNull()
    }

    @Test
    fun legacyRealPackWorksWithoutRequiringFts5Match(): Unit = runBlocking {
        val legacy = File("/data/local/tmp/ausroads-original-search.db")
        check(legacy.isFile) { "Required public legacy-pack fixture was not staged" }
        val original = SQLiteDatabase.openDatabase(legacy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT name,kind,class,lat,lon FROM search_meta WHERE name LIKE '%Adelaide%' " +
                    "ORDER BY length(name),name LIMIT 1", null,
            ).use { cursor ->
                check(cursor.moveToFirst())
                SearchResult(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getDouble(3), cursor.getDouble(4))
            }
        }
        repository.open(legacy.absolutePath)
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        assertThat(repository.search("Adelaide").first()).isEqualTo(original)
        assertThat(repository.search("Adelaide").first().placeKey()).isEqualTo(original.placeKey())
        assertThat(repository.discover(DiscoveryRequest(-34.9285, 138.6007, query = "coffee")))
            .isNotEmpty()
    }

    @Test
    fun repositoryFaultWitnessesExcludeSqlitePrivatePayloads(): Unit = runBlocking {
        val openSecret = "PRIVATE_COORDINATES_OPEN_${System.nanoTime()}"
        val broken = File(context.cacheDir, "log-open-${System.nanoTime()}.db").also { files += it }
        SQLiteDatabase.openOrCreateDatabase(broken, null).use { db ->
            db.execSQL("CREATE VIEW search_meta AS SELECT name,kind,class,lat,lon FROM $openSecret")
        }
        repository.open(broken.absolutePath)
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.FAILED)

        val querySecret = "PRIVATE_COORDINATES_QUERY_${System.nanoTime()}"
        val valid = fixture { db ->
            insert(db, "Valid fixture", "amenity=cafe")
            db.execSQL("ALTER TABLE search_meta RENAME TO $querySecret")
            db.execSQL("CREATE VIEW search_meta AS SELECT name,kind,class,lat,lon FROM $querySecret")
        }
        repository.open(valid.absolutePath)
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        SQLiteDatabase.openDatabase(valid.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE $querySecret")
        }
        assertThrows(SQLiteException::class.java) { runBlocking { repository.search("private_entered_query") } }
        val log = repositoryLog()
        assertThat(log).contains("component=places seam=index-open class=contract-drift")
        assertThat(log).contains("component=places seam=keyword-query class=contract-drift")
        assertThat(log).doesNotContain(openSecret)
        assertThat(log).doesNotContain(querySecret)
        assertThat(log).doesNotContain("private_entered_query")
    }

    private fun repositoryLog(): String {
        val command = "logcat -d -t 400 --pid=${android.os.Process.myPid()} -v brief -s FtsSearchRepository:E"
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun request(query: String = "", category: ExploreCategory? = null) =
        DiscoveryRequest(-34.92, 138.62, 20, query, category)

    private fun row(
        name: Any? = "Fixture",
        kind: Any? = "poi",
        tag: Any? = "amenity=cafe",
        lat: Any? = -34.92,
        lon: Any? = 138.62,
    ): Array<Any?> = arrayOf(name, kind, tag, lat, lon)

    private fun rawFixture(rows: List<Array<Any?>>): File {
        val file = File(context.cacheDir, "domain-${System.nanoTime()}.db").also { files += it }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // No affinity/constraints: malformed stored types must reach the real load validator.
            db.execSQL("CREATE TABLE search_meta(name,kind,class,lat,lon)")
            rows.forEach { db.execSQL("INSERT INTO search_meta VALUES (?,?,?,?,?)", it) }
        }
        return file
    }

    private fun fixture(populate: (SQLiteDatabase) -> Unit): File {
        val file = File(context.cacheDir, "discovery-${System.nanoTime()}.db").also { files += it }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE search_meta(id INTEGER PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,class TEXT,lat REAL NOT NULL,lon REAL NOT NULL)"
            )
            populate(db)
        }
        return file
    }

    private fun insert(
        db: SQLiteDatabase,
        name: String,
        tag: String,
        lat: Double = -34.92,
        lon: Double = 138.62,
        kind: String = "poi",
    ) {
        db.execSQL(
            "INSERT INTO search_meta(name,kind,class,lat,lon) VALUES (?,?,?,?,?)",
            arrayOf<Any>(name, kind, tag, lat, lon),
        )
    }
}
