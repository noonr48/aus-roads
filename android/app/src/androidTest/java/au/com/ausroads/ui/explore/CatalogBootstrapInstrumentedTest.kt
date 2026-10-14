package au.com.ausroads.ui.explore

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.collectInstalledPacks
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.di.SearchInitializer
import au.com.ausroads.di.SearchRoadHazardSource
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.ExploreCategory
import au.com.ausroads.offline.search.FtsSearchRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated files-only contexts: no owner packs/preferences or production directories are changed.
 */
@RunWith(AndroidJUnit4::class)
class CatalogBootstrapInstrumentedTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val roots = mutableListOf<File>()
    private val repository = FtsSearchRepository()

    @After
    fun cleanup() {
        repository.close()
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun missingAndCorruptCachedBundledCatalogReextractsAndBecomesReady(): Unit = runBlocking {
        val context = isolated()
        val initializer = SearchInitializer(context, repository, repository, PlacesHealth(context))
        initializer.initialize()
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        assertThat(repository.search("Adelaide")).isNotEmpty()
        val cache =
            context.noBackupFilesDir.listFiles()!!.single { it.name.startsWith("catalog-sa-") }
        repository.close()
        cache.writeText("corrupt derived cache")
        initializer.initialize()
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        assertThat(repository.search("Adelaide")).isNotEmpty()
        assertThat(initializer.usingBundled.value).isTrue()
    }

    @Test
    fun queryableSameLengthBundledCacheStillRequiresMetadataHash(): Unit = runBlocking {
        val context = isolated()
        val initializer = SearchInitializer(context, repository, repository, PlacesHealth(context))
        initializer.initialize()
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        val metadata = context.assets.open("catalog/sa-search.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        assertThat(metadata.getLong("rows")).isEqualTo(129_957L)
        val cache = context.noBackupFilesDir.listFiles()!!.single { it.name.startsWith("catalog-sa-") }
        repository.close()
        val digest = MessageDigest.getInstance("SHA-256")
        val originalDigest = digest.digest(cache.readBytes())
        // Default writable open creates locale metadata before any caller SQL.
        // Demonstrate that separate mutation on a disposable copy, not the hash fixture.
        val localeControl = cache.copyTo(File(context.filesDir, "locale-default-control.db"))
        SQLiteDatabase.openDatabase(localeControl.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='android_metadata'", null).use { cursor ->
                check(cursor.moveToFirst())
                assertThat(cursor.getLong(0)).isEqualTo(1L)
            }
        }
        assertThat(localeControl.length()).isGreaterThan(metadata.getLong("bytes"))
        SQLiteDatabase.openDatabase(
            cache.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { db ->
            db.rawQuery("SELECT count(*) FROM search_meta", null).use { cursor ->
                check(cursor.moveToFirst())
                assertThat(cursor.getLong(0)).isEqualTo(metadata.getLong("rows"))
            }
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='android_metadata'", null).use { cursor ->
                check(cursor.moveToFirst())
                assertThat(cursor.getLong(0)).isEqualTo(0L)
            }
            assertThat(cache.length()).isEqualTo(metadata.getLong("bytes"))
            assertThat(digest.digest(cache.readBytes()).contentEquals(originalDigest)).isTrue()
            db.execSQL("UPDATE search_meta SET name='X'||substr(name,2) WHERE name='Adelaide'")
        }
        assertThat(cache.length()).isEqualTo(metadata.getLong("bytes"))
        assertThat(digest.digest(cache.readBytes()).contentEquals(originalDigest)).isFalse()
        repository.open(cache.path) // still healthy SQLite; hash verification must be the rejecting sensor
        assertThat(repository.search("Xdelaide")).isNotEmpty()
        initializer.initialize()
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        assertThat(repository.search("Adelaide").map { it.name }).contains("Adelaide")
        assertThat(repository.search("Xdelaide")).isEmpty()
    }

    @Test
    fun malformedAdoptedRowsFallBackAndKeepFixedPersistentWitness(): Unit = runBlocking {
        val context = isolated()
        val health = PlacesHealth(context)
        val initializer = SearchInitializer(context, repository, repository, health)
        val malformed = listOf(
            arrayOf<Any?>(null, "poi", "amenity=cafe", -34.92, 138.62),
            arrayOf<Any?>(byteArrayOf(1), "poi", "amenity=cafe", -34.92, 138.62),
            arrayOf<Any?>("ADOPTED-INVALID", "unknown", "amenity=cafe", -34.92, 138.62),
            arrayOf<Any?>("ADOPTED-INVALID", "poi", "amenity=cafe", "not-a-number", 138.62),
            arrayOf<Any?>("ADOPTED-INVALID", "park", "amenity=cafe", -34.92, 138.62),
        )
        malformed.forEachIndexed { index, row ->
            val adopted = adoptedCatalog(context, "invalid-$index.db", "ADOPTED-VALID")
            SQLiteDatabase.openDatabase(adopted.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO search_meta VALUES (?,?,?,?,?)", row)
            }
            adopt(context, adopted)
            initializer.initialize()
            assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
            assertThat(initializer.usingBundled.value).isTrue()
            assertThat(repository.search("Adelaide")).isNotEmpty()
            assertThat(repository.search("ADOPTED")).isEmpty()
        }
        val worker = PlacesHealth::class.java.getDeclaredField("writer").apply { isAccessible = true }
            .get(health) as ExecutorService
        worker.submit {}.get(5, TimeUnit.SECONDS)
        val witness = File(context.noBackupFilesDir, "places-health.json").readText()
        assertThat(JSONObject(witness).getJSONObject("CATALOG_OPEN").getLong("failures"))
            .isEqualTo(malformed.size.toLong())
        assertThat(JSONObject(witness).getJSONObject("CATALOG_BOOTSTRAP").has("last_success_ms")).isTrue()
        assertThat(witness).doesNotContain("ADOPTED")
        assertThat(witness).doesNotContain("34.92")
        assertThat(witness).doesNotContain(context.filesDir.path)
    }

    @Test
    fun bootCollectorRegistrationFailureStillBootstrapsButCancellationDoesNot(): Unit = runBlocking {
        val context = isolated()
        val initializer = SearchInitializer(context, repository, repository, PlacesHealth(context))
        val calls = AtomicInteger()
        withContext(Dispatchers.IO) {
            collectInstalledPacks({ throw IllegalStateException("private registration payload") }) {
                calls.incrementAndGet()
                initializer.initialize()
            }
        }
        assertThat(calls.get()).isEqualTo(1)
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        assertThat(repository.search("Adelaide")).isNotEmpty()
        val generation = repository.generation.value
        assertThrows(CancellationException::class.java) {
            runBlocking {
                collectInstalledPacks({ throw CancellationException("registration canceled") }) {
                    calls.incrementAndGet()
                    initializer.initialize()
                }
            }
        }
        assertThat(calls.get()).isEqualTo(1)
        assertThat(repository.generation.value).isEqualTo(generation)
    }

    @Test
    fun bootCollectorLoadsReplacementAlreadyCurrentBeforeRegistration(): Unit = runBlocking {
        val context = isolated()
        val initializer = SearchInitializer(context, repository, repository, PlacesHealth(context))
        val first = adoptedCatalog(context, "first.db", "EARLY")
        val second = adoptedCatalog(context, "second.db", "CURRENT")
        val installed = MutableStateFlow<InstalledPack?>(null)
        adopt(context, first)
        installed.value = record(first)
        adopt(context, second)
        installed.value = record(second) // arrives before the production collector is registered
        val initialized = CompletableDeferred<Unit>()
        val worker = launch(Dispatchers.IO) {
            collectInstalledPacks({ installed }) {
                initializer.initialize()
                initialized.complete(Unit)
            }
        }
        try {
            withTimeout(5_000) { initialized.await() }
            assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
            assertThat(repository.search("CURRENT").single().name).isEqualTo("CURRENT")
            assertThat(repository.search("EARLY")).isEmpty()
            assertThat(initializer.usingBundled.value).isFalse()
        } finally {
            worker.cancelAndJoin()
        }
    }

    @Test
    fun bootCollectorKeepsReplacementDuringInitialLoadSerialized(): Unit = runBlocking {
        val context = isolated()
        val initializer = SearchInitializer(context, repository, repository, PlacesHealth(context))
        val first = adoptedCatalog(context, "first.db", "FIRST")
        val second = adoptedCatalog(context, "second.db", "SECOND")
        adopt(context, first)
        val installed = MutableStateFlow<InstalledPack?>(record(first))
        val firstLoaded = CompletableDeferred<Unit>()
        val secondLoaded = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val active = AtomicInteger()
        val worker = launch(Dispatchers.IO) {
            collectInstalledPacks({ installed }) {
                assertThat(active.incrementAndGet()).isEqualTo(1)
                try {
                    initializer.initialize()
                    if (calls.incrementAndGet() == 1) {
                        firstLoaded.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                    } else secondLoaded.complete(Unit)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        try {
            withTimeout(5_000) { firstLoaded.await() }
            assertThat(repository.search("FIRST")).isNotEmpty()
            val generation = repository.generation.value
            adopt(context, second)
            installed.value = record(second) // same version, different full installed record
            release.countDown()
            withTimeout(5_000) { secondLoaded.await() }
            assertThat(calls.get()).isEqualTo(2)
            assertThat(repository.generation.value).isGreaterThan(generation)
            assertThat(repository.search("SECOND").single().name).isEqualTo("SECOND")
            assertThat(repository.search("FIRST")).isEmpty()
        } finally {
            release.countDown()
            worker.cancelAndJoin()
        }
    }

    @Test
    fun adoptedCustomCatalogWinsOverOrphanAndInvalidAdoptionFallsBack(): Unit = runBlocking {
        val context = isolated()
        val base = File(context.filesDir, "mappacks/au-sa").apply { mkdirs() }
        val adopted = File(base, "v2/custom/places.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(adopted, null).use { db ->
            db.execSQL(
                "CREATE TABLE search_meta(id INTEGER PRIMARY KEY,name TEXT,kind TEXT,class TEXT,lat REAL,lon REAL)"
            )
            db.execSQL(
                "INSERT INTO search_meta VALUES (1,'ADOPTED','poi','amenity=cafe',-34.92,138.62)"
            )
        }
        File(base, "v999").mkdirs()
        File(base, "v999/search.db").writeText("orphan")
        val state = File(base, "current.json")
        state.writeText(
            JSONObject().put("version", "2").put("searchPath", "custom/places.db").toString()
        )
        val initializer = SearchInitializer(context, repository, repository, PlacesHealth(context))
        initializer.initialize()
        assertThat(repository.search("ADOPTED").single().name).isEqualTo("ADOPTED")
        assertThat(initializer.usingBundled.value).isFalse()
        state.writeText("broken adoption metadata")
        initializer.initialize()
        assertThat(repository.search("Adelaide")).isNotEmpty()
        assertThat(initializer.usingBundled.value).isTrue()
    }

    @Test
    fun healthStartWriteFailureDoesNotKillWorkerAndFixedCountersPersist(): Unit = runBlocking {
        val context = isolated()
        val directory = context.noBackupFilesDir
        directory.deleteRecursively()
        directory.writeText("block directory")
        val health = PlacesHealth(context)
        // The real SDK accepts null for a failed startWrite; pin this API rather than infer an NPE.
        AtomicFile(File(directory, "null-output-control")).failWrite(null)
        val workerField =
            PlacesHealth::class.java.getDeclaredField("writer").apply { isAccessible = true }
        val worker = workerField.get(health) as ExecutorService
        health.failure(PlacesSeam.CATALOG_QUERY)
        worker.submit {}.get(5, TimeUnit.SECONDS) // proves the write was attempted while blocked
        directory.delete()
        directory.mkdirs()
        health.failure(PlacesSeam.CATALOG_QUERY)
        health.success(PlacesSeam.CATALOG_QUERY)
        worker.submit {}.get(5, TimeUnit.SECONDS)
        val file = File(directory, "places-health.json")
        val row = JSONObject(file.readText()).getJSONObject("CATALOG_QUERY")
        assertThat(row.getLong("failures")).isEqualTo(2)
        assertThat(row.has("last_failure_ms")).isTrue()
        assertThat(row.has("last_success_ms")).isTrue()
        assertThat(file.readText()).doesNotContain("Adelaide")
    }

    @Test
    fun oversizedHealthStateHasReadAlarmAndNoisyQueryCannotHideNewSeam(): Unit = runBlocking {
        val context = isolated()
        val file = File(context.noBackupFilesDir, "places-health.json")
        file.writeText("x".repeat(16_385))
        val health = PlacesHealth(context)
        val workerField =
            PlacesHealth::class.java.getDeclaredField("writer").apply { isAccessible = true }
        val worker = workerField.get(health) as ExecutorService
        repeat(7) { health.failure(PlacesSeam.CATALOG_QUERY) }
        health.failure(PlacesSeam.PREFERENCES_WRITE)
        worker.submit {}.get(5, TimeUnit.SECONDS)
        val state = JSONObject(file.readText())
        assertThat(state.getLong("health_read_failures")).isEqualTo(1)
        assertThat(state.getJSONObject("CATALOG_QUERY").getLong("failures")).isEqualTo(7)
        assertThat(state.getJSONObject("PREFERENCES_WRITE").getLong("failures")).isEqualTo(1)
        val emittedField =
            PlacesHealth::class.java.getDeclaredField("emitted").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val emitted = emittedField.get(health) as Map<String, AtomicInteger>
        assertThat(emitted["PREFERENCES_WRITE/not-joined"]!!.get()).isEqualTo(1)
        assertThat(emitted["health-read/contract-drift"]!!.get()).isEqualTo(1)
    }

    @Test
    fun corruptVisitMetadataClearUsesRealDataStoreAndPreservesOtherPreferences(): Unit =
        runBlocking {
            val context = isolated()
            val store = DataStoreExplorationStore(context)
            store.setInterest(ExploreCategory.NATURE, true)
            store.setPreferNew(false)
            val field =
                DataStoreExplorationStore::class.java.getDeclaredField("dataStore").apply {
                    isAccessible = true
                }
            @Suppress("UNCHECKED_CAST") val dataStore = field.get(store) as DataStore<Preferences>
            dataStore.edit { it[stringSetPreferencesKey("visits")] = setOf("invalid record") }
            val failure = runCatching { store.preferences.first() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            store.clearVisits() // Explicit confirmed visit-only recovery, never an automatic reset.
            val loaded = store.preferences.first()
            assertThat(loaded.visits).isEmpty()
            assertThat(loaded.interests).containsExactly(ExploreCategory.NATURE)
            assertThat(loaded.preferNew).isFalse()
        }

    @Test
    fun absentCameraTableIsSupportedButMalformedSchemaCrossesRealPersistentWitness(): Unit =
        runBlocking {
            val context = isolated()
            val index = File(context.filesDir, "camera-control.db")
            SQLiteDatabase.openOrCreateDatabase(index, null).use { db ->
                db.execSQL(
                    "CREATE TABLE search_meta(name TEXT, kind TEXT, class TEXT, lat REAL, lon REAL)"
                )
                db.execSQL(
                    "INSERT INTO search_meta VALUES ('Fixture', 'poi', 'amenity=cafe', -34.92, 138.62)"
                )
            }
            repository.open(index.absolutePath)
            assertThat(repository.camerasNear(-34.92, 138.62)).isEmpty()
            repository.close()
            SQLiteDatabase.openOrCreateDatabase(index, null).use { db ->
                db.execSQL("CREATE TABLE road_cameras(broken TEXT)")
            }
            repository.open(index.absolutePath)
            val health = PlacesHealth(context)
            val models = ViewModelStore()
            withContext(Dispatchers.Main) {
                val cameras = au.com.ausroads.ui.map.CamerasViewModel(repository, health)
                models.put("camera-control", cameras)
                cameras.onViewportChanged(-34.92, 138.62, 3_000.0)
            }
            val witness = File(context.noBackupFilesDir, "places-health.json")
            try {
                withTimeout(5_000) {
                    while (
                        !runCatching {
                                JSONObject(witness.readText())
                                    .getJSONObject("CAMERAS_QUERY")
                                    .getLong("failures") > 0
                            }
                            .getOrDefault(false)
                    ) delay(20)
                }
                repository.close()
                SQLiteDatabase.openOrCreateDatabase(index, null).use { db ->
                    db.execSQL("DROP TABLE road_cameras")
                    db.execSQL(
                        "CREATE TABLE road_cameras(lat REAL, lon REAL, maxspeed_kmh INTEGER, way_name TEXT)"
                    )
                    db.execSQL("INSERT INTO road_cameras VALUES (-34.92, 138.62, 60, 'Recovered')")
                }
                repository.open(
                    index.absolutePath
                ) // unchanged viewport must refresh on the new generation
                withTimeout(5_000) {
                    while (
                        !runCatching {
                                JSONObject(witness.readText())
                                    .getJSONObject("CAMERAS_QUERY")
                                    .has("last_success_ms")
                            }
                            .getOrDefault(false)
                    ) delay(20)
                }
                val row = JSONObject(witness.readText()).getJSONObject("CAMERAS_QUERY")
                assertThat(row.getLong("failures")).isEqualTo(1)
                assertThat(row.has("last_success_ms")).isTrue()
            } finally {
                withContext(Dispatchers.Main) { models.clear() }
            }
        }

    @Test fun absentRoadSpeedIsSupportedButMalformedPresentTableIsNotNoData(): Unit = runBlocking {
        val context = isolated()
        val index = File(context.filesDir, "speed-control.db")
        SQLiteDatabase.openOrCreateDatabase(index, null).use { db ->
            db.execSQL("CREATE TABLE search_meta(name TEXT,kind TEXT,class TEXT,lat REAL,lon REAL)")
            db.execSQL("INSERT INTO search_meta VALUES ('Fixture','poi','amenity=cafe',-34.92,138.62)")
        }
        repository.open(index.path)
        assertThat(repository.maxspeedNear(-34.92, 138.62)).isNull()
        repository.close()
        SQLiteDatabase.openDatabase(index.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE road_speed(broken TEXT)")
        }
        repository.open(index.path)
        assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
        val failure = runCatching { repository.maxspeedNear(-34.92, 138.62) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SQLiteException::class.java)
        val health = PlacesHealth(context)
        val source = SearchRoadHazardSource(repository, health)
        val unknown = source.contextAt(-34.92, 138.62, 2_000.0)
        assertThat(unknown.speedLimitKmh).isNull()
        assertThat(unknown.cameras).isEmpty()
        val writer = PlacesHealth::class.java.getDeclaredField("writer").apply { isAccessible = true }
            .get(health) as ExecutorService
        writer.submit {}.get(5, TimeUnit.SECONDS)
        val witness = File(context.noBackupFilesDir, "places-health.json")
        assertThat(JSONObject(witness.readText()).getJSONObject("ROAD_SPEED_QUERY").getLong("failures"))
            .isEqualTo(1)
        val generation = repository.generation.value
        SQLiteDatabase.openDatabase(index.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE road_speed")
            db.execSQL("CREATE TABLE road_speed(lat REAL,lon REAL,maxspeed_kmh INTEGER)")
            db.execSQL("INSERT INTO road_speed VALUES (-34.92,138.62,60)")
        }
        assertThat(repository.maxspeedNear(-34.92, 138.62)).isEqualTo(60)
        assertThat(source.contextAt(-34.92, 138.62, 2_000.0).speedLimitKmh).isEqualTo(60)
        writer.submit {}.get(5, TimeUnit.SECONDS)
        val row = JSONObject(witness.readText()).getJSONObject("ROAD_SPEED_QUERY")
        assertThat(row.getLong("failures")).isEqualTo(1)
        assertThat(row.has("last_success_ms")).isTrue()
        assertThat(row.toString()).doesNotContain("Fixture")
        assertThat(row.toString()).doesNotContain("34.92")
        assertThat(repository.generation.value).isEqualTo(generation)
    }

    private fun adoptedCatalog(context: Context, filename: String, name: String): File {
        val file = File(context.filesDir, "mappacks/au-sa/vproof/$filename").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE search_meta(name,kind,class,lat,lon)")
            db.execSQL("INSERT INTO search_meta VALUES (?,'poi','amenity=cafe',-34.92,138.62)", arrayOf(name))
        }
        return file
    }

    private fun adopt(context: Context, file: File) {
        File(context.filesDir, "mappacks/au-sa/current.json").writeText(
            JSONObject().put("version", "proof").put("searchPath", file.name).toString()
        )
    }

    private fun record(file: File) = InstalledPack(
        version = "proof", regionCode = "AU-SA", installedAt = Instant.fromEpochMilliseconds(0),
        totalSizeBytes = file.length(), tilesPath = "tiles.mbtiles", searchPath = file.name,
        manifestSha256 = "a".repeat(64),
    )

    private fun isolated(): Context {
        val root =
            File(app.cacheDir, "catalog-proof-${System.nanoTime()}").apply {
                mkdirs()
                roots += this
            }
        val files = File(root, "files").apply { mkdirs() }
        val noBackup = File(root, "no-backup").apply { mkdirs() }
        return object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = files

            override fun getNoBackupFilesDir(): File = noBackup
        }
    }
}
