package au.com.ausroads.ui.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.ausroads.AusRoadsApp
import au.com.ausroads.MainActivity
import au.com.ausroads.R
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.FtsSearchRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Actual app/observer/UI with scoped disposable fixtures, never the owner's phone or user data. */
@RunWith(AndroidJUnit4::class)
class CatalogRefreshJourneyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context.applicationContext as AusRoadsApp

    @Before fun guestOnly(): Unit = runBlocking {
        check(Build.MODEL.startsWith("sdk_gphone"))
        check(!Files.exists(File(context.filesDir, "mappacks/au-sa/current.json").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "component=native-proof seam=pack-authority class=existing-state"
        }
        check(PackStateStore(context).readCurrent() == null)
        check(app.mapPackManager.get().installed.value == null)
    }

    @Test fun malformedCameraTableRecoversThroughVisibleRetryAtTheSameViewport(): Unit = runBlocking {
        proveCameraFixtureRenameFailurePreservesOriginal()
        val map = awaitMap()
        val repository = repository()
        val database = FtsSearchRepository::class.java.getDeclaredField("db")
            .apply { isAccessible = true }.get(repository) as SQLiteDatabase
        val path = File(database.path).canonicalFile
        check(path.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath()))
        val savedTable = "camera_fixture_${System.nanoTime()}"
        val fixture = CameraTableFixture(savedTable)
        lateinit var originalCamera: CameraPosition
        compose.runOnUiThread { originalCamera = map.cameraPosition }
        val witness = File(context.noBackupFilesDir, "places-health.json")
        val beforeFailures = cameraFailures(witness)
        var bodyFailure: Throwable? = null
        try {
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use(fixture::install)
            compose.runOnUiThread {
                map.cameraPosition = CameraPosition.Builder(map.cameraPosition)
                    .target(LatLng(-34.925, 138.625)).zoom(13.0).build()
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.map_cameras_retry))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.map_cameras_error)).assertIsDisplayed()
            compose.waitUntil(5_000) { cameraFailures(witness) > beforeFailures }
            screenshot("camera-retry-error.png")
            val generation = repository.generation.value
            lateinit var retainedCamera: CameraPosition
            compose.runOnUiThread { retainedCamera = map.cameraPosition }
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE road_cameras")
                db.execSQL("CREATE TABLE road_cameras(lat REAL,lon REAL,maxspeed_kmh INTEGER,way_name TEXT)")
                db.execSQL("INSERT INTO road_cameras VALUES (-34.925,138.625,60,'Native retry camera')")
            }
            assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
            assertThat(repository.generation.value).isEqualTo(generation)
            val retryAt = System.currentTimeMillis()
            compose.onNodeWithText(context.getString(R.string.map_cameras_retry)).performClick()
            compose.waitUntil(10_000) {
                var recovered = false
                compose.runOnUiThread {
                    val pixel = map.projection.toScreenLocation(LatLng(-34.925, 138.625))
                    recovered = map.queryRenderedFeatures(pixel, "cameras-points")
                        .any { it.getStringProperty("id") == "-34.925000,138.625000" }
                }
                recovered
            }
            compose.onNodeWithText(context.getString(R.string.map_cameras_error)).assertDoesNotExist()
            compose.waitUntil(5_000) {
                runCatching { JSONObject(witness.readText()).getJSONObject("CAMERAS_QUERY")
                    .optLong("last_success_ms") >= retryAt }.getOrDefault(false)
            }
            assertThat(repository.generation.value).isEqualTo(generation)
            assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
            compose.runOnUiThread { assertThat(map.cameraPosition).isEqualTo(retainedCamera) }
            screenshot("camera-retry-recovered.png")
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            val failures = mutableListOf<Throwable>()
            runCatching {
                SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use(fixture::restore)
            }.exceptionOrNull()?.let(failures::add)
            // DDL changes a derived cache's byte hash. Let its normal validated
            // re-extraction path restore it, never write bytes through an open database.
            runCatching { withContext(Dispatchers.IO) { app.searchInitializer.initialize() } }
                .exceptionOrNull()?.let(failures::add)
            runCatching { compose.runOnUiThread { map.cameraPosition = originalCamera } }
                .exceptionOrNull()?.let(failures::add)
            val original = bodyFailure
            if (original != null) failures.filter { it !== original }.forEach(original::addSuppressed)
            else failures.firstOrNull()?.let { first ->
                failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
                throw first
            }
        }
    }

    @Test fun sameVersionNewInstalledRecordReopensTheRealCatalog(): Unit = runBlocking {
        val map = awaitMap()
        val repository = repository()
        val manager = app.mapPackManager.get()
        @Suppress("UNCHECKED_CAST")
        val installed = manager.javaClass.getDeclaredField("_installed").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<InstalledPack?>
        val store = PackStateStore(context)
        val version = "same-record-${System.nanoTime()}"
        val directory = store.packDir(version).apply { mkdirs() }
        val tiles = File(directory, "tiles.mbtiles")
        context.assets.open("maptile/adelaide-test-tiles.mbtiles").use { input ->
            tiles.outputStream().use(input::copyTo)
        }
        catalog(File(directory, "alpha.db"), "Native Alpha")
        catalog(File(directory, "beta.db"), "Native Beta")
        val first = InstalledPack(version, "AU-SA", Instant.fromEpochMilliseconds(0), tiles.length(),
            "tiles.mbtiles", "alpha.db", null, "a".repeat(64))
        val replacement = first.copy(searchPath = "beta.db", installedAt = Instant.fromEpochMilliseconds(1),
            manifestSha256 = "b".repeat(64))
        val alphaFile = File(directory, "alpha.db")
        val betaFile = File(directory, "beta.db")
        val bundledHash = context.assets.open("catalog/sa-search.json").bufferedReader().use {
            JSONObject(it.readText()).getString("sha256")
        }
        val bundledFile = File(context.noBackupFilesDir, "catalog-sa-${bundledHash.take(16)}.db")
        try {
            val originalGeneration = repository.generation.value
            assertThat(catalogPublished(repository, originalGeneration, alphaFile)).isFalse()
            publicationWitness("before-alpha", repository, alphaFile, originalGeneration)
            store.writeCurrent(first)
            installed.value = first // the existing publisher's full-record stream, not a UI replacement
            awaitGeneration(repository, originalGeneration, alphaFile)
            assertThat(catalogPublished(repository, originalGeneration, betaFile)).isFalse()
            publicationWitness("generation-alpha", repository, alphaFile, originalGeneration)
            val alpha = repository.search("Native Alpha")
            publicationWitness("query-alpha", repository, File(directory, "alpha.db"), originalGeneration, alpha.size)
            assertThat(alpha.single().name).isEqualTo("Native Alpha")
            val firstGeneration = repository.generation.value
            publicationWitness("before-beta", repository, File(directory, "beta.db"), firstGeneration)
            store.writeCurrent(replacement)
            installed.value = replacement
            awaitGeneration(repository, firstGeneration, betaFile)
            assertThat(catalogPublished(repository, firstGeneration, alphaFile)).isFalse()
            publicationWitness("generation-beta", repository, betaFile, firstGeneration)
            val beta = repository.search("Native Beta")
            publicationWitness("query-beta", repository, File(directory, "beta.db"), firstGeneration, beta.size)
            assertThat(beta.single().name).isEqualTo("Native Beta")
            assertThat(repository.search("Native Alpha")).isEmpty()
            assertThat(installed.value?.version).isEqualTo(first.version)
        } finally {
            val generation = repository.generation.value
            store.clearCurrent()
            installed.value = null
            awaitGeneration(repository, generation, bundledFile)
            compose.waitUntil(15_000) {
                var restored = false
                compose.runOnUiThread {
                    restored = map.style?.json?.let { JSONObject(it).getJSONObject("sources")
                        .getJSONObject("aus-roads-tiles").getString("url") } ==
                        "mbtiles://${File(context.filesDir, "adelaide-test-tiles.mbtiles").canonicalPath}"
                }
                restored
            }
            assertThat(repository.search("Adelaide")).isNotEmpty()
            directory.deleteRecursively()
        }
    }

    private fun catalog(file: File, name: String) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE search_meta(id INTEGER PRIMARY KEY,name TEXT,kind TEXT,class TEXT,lat REAL,lon REAL)")
            db.execSQL("INSERT INTO search_meta VALUES (1,?,'poi','amenity=cafe',-34.92,138.62)", arrayOf(name))
        }
    }

    private fun repository(): FtsSearchRepository = app.searchInitializer.javaClass
        .getDeclaredField("searchRepository").apply { isAccessible = true }
        .get(app.searchInitializer) as FtsSearchRepository

    private fun awaitGeneration(repository: FtsSearchRepository, prior: Long, expected: File) {
        compose.waitUntil(10_000) { catalogPublished(repository, prior, expected) }
    }

    /** An arbitrary READY generation (including late boot) is not adoption acknowledgement. */
    private fun catalogPublished(repository: FtsSearchRepository, prior: Long, expected: File): Boolean {
        val lock = FtsSearchRepository::class.java.getDeclaredField("databaseLock")
            .apply { isAccessible = true }.get(repository)
        return synchronized(lock) {
            val database = FtsSearchRepository::class.java.getDeclaredField("db")
                .apply { isAccessible = true }.get(repository) as SQLiteDatabase?
            repository.generation.value > prior && repository.availability.value == DiscoveryAvailability.READY &&
                database?.isOpen == true && File(database.path).canonicalFile == expected.canonicalFile
        }
    }

    /** Sample under the actual repository lock; no re-open, wait, setter or query substitution. */
    private fun publicationWitness(
        stage: String,
        repository: FtsSearchRepository,
        expected: File,
        prior: Long,
        count: Int = -1,
    ) {
        val lock = FtsSearchRepository::class.java.getDeclaredField("databaseLock")
            .apply { isAccessible = true }.get(repository)
        val record = synchronized(lock) {
            val database = FtsSearchRepository::class.java.getDeclaredField("db")
                .apply { isAccessible = true }.get(repository) as SQLiteDatabase?
            JSONObject().put("component", "native-catalog-publication")
                .put("stage", stage).put("elapsed_ns", SystemClock.elapsedRealtimeNanos())
                .put("prior_generation", prior).put("generation", repository.generation.value)
                .put("availability", repository.availability.value.name)
                .put("intended_database_open", database?.path?.let { File(it).canonicalFile } == expected.canonicalFile)
                .put("database_open", database?.isOpen == true).put("query_count", count)
        }
        File(context.cacheDir, "catalog-publication.jsonl").appendText(record.toString() + "\n")
    }

    private fun proveCameraFixtureRenameFailurePreservesOriginal() {
        SQLiteDatabase.create(null).use { database ->
            database.execSQL("CREATE TABLE road_cameras(sentinel TEXT)")
            database.execSQL("INSERT INTO road_cameras VALUES ('original')")
            database.execSQL("CREATE TABLE camera_fixture_collision(sentinel TEXT)")
            val fixture = CameraTableFixture("camera_fixture_collision")
            val failure = runCatching { fixture.install(database) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(android.database.sqlite.SQLiteException::class.java)
            fixture.restore(database) // A failed rename owns neither original nor fixture.
            database.rawQuery("SELECT sentinel FROM road_cameras", null).use {
                check(it.moveToFirst() && it.getString(0) == "original" && !it.moveToNext()) {
                    "component=native-proof seam=camera-fixture-rename class=original-not-preserved"
                }
            }
        }
    }

    private fun cameraFailures(file: File): Long = runCatching {
        JSONObject(file.readText()).optJSONObject("CAMERAS_QUERY")?.optLong("failures") ?: 0L
    }.getOrDefault(0L)

    private fun screenshot(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun awaitMap(): MapLibreMap {
        var view: MapView? = null
        var map: MapLibreMap? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread { view = findMap(compose.activity.window.decorView) }
            view != null
        }
        compose.runOnUiThread { view!!.getMapAsync { map = it; it.triggerRepaint() } }
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnUiThread {
                ready = map?.style != null && map?.queryRenderedFeatures(
                    RectF(0f, 0f, view!!.width.toFloat(), view!!.height.toFloat()), "road-label", "place-suburb")
                    ?.isNotEmpty() == true
            }
            ready
        }
        return requireNotNull(map)
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        return null
    }
}

/** Own only successful DDL mutations; a failed rename never authorizes deleting the original. */
private class CameraTableFixture(private val savedTable: String) {
    private var originalRenamed = false
    private var fixtureCreated = false

    fun install(database: SQLiteDatabase) {
        val originalExists = database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='road_cameras'", null,
        ).use { it.moveToFirst() }
        if (originalExists) {
            database.execSQL("ALTER TABLE road_cameras RENAME TO $savedTable")
            originalRenamed = true
        }
        database.execSQL("CREATE TABLE road_cameras(broken TEXT)")
        fixtureCreated = true
    }

    fun restore(database: SQLiteDatabase) {
        if (fixtureCreated) {
            database.execSQL("DROP TABLE IF EXISTS road_cameras")
            fixtureCreated = false
        }
        if (originalRenamed) {
            database.execSQL("ALTER TABLE $savedTable RENAME TO road_cameras")
            originalRenamed = false
        }
    }
}
