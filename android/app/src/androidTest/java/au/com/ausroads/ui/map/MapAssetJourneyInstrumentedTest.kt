package au.com.ausroads.ui.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.MainActivity
import au.com.ausroads.R
import au.com.ausroads.AusRoadsApp
import au.com.ausroads.data.pins.PinDatabase
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.ui.explore.DataStoreExplorationStore
import au.com.ausroads.offline.search.ExploreCategory
import au.com.ausroads.offline.search.DiscoveryRequest
import au.com.ausroads.offline.search.FtsSearchRepository
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.StandardGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Actual application content; controlled adoption/cache fixtures, never Pixel or a UI replacement. */
@RunWith(AndroidJUnit4::class)
class MapAssetJourneyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    @get:Rule val testName = TestName()
    private val ownedRestorations = mutableListOf<() -> Unit>()
    private val observedViews = mutableSetOf<MapView>()
    private val frameCount = AtomicInteger()
    private val styleCount = AtomicInteger()
    private val sourceChangeCount = AtomicInteger()
    private val nativeErrorCount = AtomicInteger()
    private val lastFrameFully = AtomicBoolean()
    private val frameListener = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
        frameCount.incrementAndGet()
        lastFrameFully.set(fully) // diagnostic only: Lab1042 healthy frames also reported false.
    }
    private val styleListener = MapView.OnDidFinishLoadingStyleListener { styleCount.incrementAndGet(); Unit }
    private val sourceListener = MapView.OnSourceChangedListener { sourceChangeCount.incrementAndGet(); Unit }
    private val errorListener = MapView.OnDidFailLoadingMapListener { nativeErrorCount.incrementAndGet(); Unit }

    @After fun restoreOwnedFixtures() {
        // Registered BEFORE fixture writes/baseline assertions, not only inside a late try.
        // A native process crash can still prevent @After; cleanup code is not crash proof.
        val failures = ownedRestorations.asReversed().map { runCatching(it).isFailure }.count { it }
        ownedRestorations.clear()
        compose.runOnUiThread {
            observedViews.forEach {
                it.removeOnDidFinishRenderingFrameListener(frameListener)
                it.removeOnDidFinishLoadingStyleListener(styleListener)
                it.removeOnSourceChangedListener(sourceListener)
                it.removeOnDidFailLoadingMapListener(errorListener)
            }
            observedViews.clear()
        }
        check(failures == 0) { "component=native-proof seam=owned-restoration class=not-joined" }
    }

    @Before fun guestOnly(): Unit = runBlocking {
        check(Build.MODEL.startsWith("sdk_gphone"))
        // Decoded null also means malformed metadata. Require actual absence,
        // including refusal of a directory or dangling link, before any fixture write.
        check(!Files.exists(File(context.filesDir, "mappacks/au-sa/current.json").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "component=native-proof seam=pack-authority class=existing-state"
        }
        check(PackStateStore(context).readCurrent() == null)
        check((context.applicationContext as AusRoadsApp).mapPackManager.get().installed.value == null)
    }

    @Test fun adoptedPackReplacementAndFallbackRetainNativeViewCameraAndListeners(): Unit = runBlocking {
        val manager = (context.applicationContext as AusRoadsApp).mapPackManager.get()
        val stateField = manager.javaClass.getDeclaredField("_installed").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val installed = stateField.get(manager) as MutableStateFlow<InstalledPack?>
        val beforePack = installed.value
        val current = File(context.filesDir, "mappacks/au-sa/current.json")
        val beforeMetadata = current.takeIf { it.isFile }?.readBytes()
        val store = PackStateStore(context)
        val prefsStore = DataStoreExplorationStore(context)
        val originalPrefs = prefsStore.preferences.first()
        var pinBaseline: Map<Long, List<String?>>? = null
        val seed = SearchResult("Native retained coffee", "poi", "amenity=cafe", -34.925, 138.625)
        val pinName = "Native retained pin ${System.nanoTime()}"
        val fixtureId = "probe-${System.nanoTime()}"
        val dirs = mutableListOf<File>()
        ownedRestorations += { dirs.forEach { it.deleteRecursively() } }
        ownedRestorations += {
            if (beforeMetadata != null) current.writeBytes(beforeMetadata) else current.delete()
            installed.value = beforePack
        }
        ownedRestorations += {
            runBlocking {
                prefsStore.setVisited(seed, seed.placeKey() in originalPrefs.visits)
                prefsStore.setInterest(ExploreCategory.NATURE, ExploreCategory.NATURE in originalPrefs.interests)
                prefsStore.setPreferNew(originalPrefs.preferNew)
                assertThat(prefsStore.preferences.first() == originalPrefs).isTrue()
            }
        }
        ownedRestorations += {
            val databaseFile = context.getDatabasePath(PinDatabase.DATABASE_NAME)
            if (databaseFile.isFile) {
                SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("DELETE FROM pins WHERE name = ?", arrayOf(pinName))
                }
            }
            pinBaseline?.let { assertThat(pinSnapshot() == it).isTrue() } // never print prior private rows.
        }
        prefsStore.setInterest(ExploreCategory.NATURE, true)
        prefsStore.setPreferNew(false)
        prefsStore.setVisited(seed, true)
        val prefs = prefsStore.preferences.first()
        val map = awaitRenderedMap(requireInteraction = true)
        val view = findMap(compose.activity.window.decorView)!!
        val originalPins = pinSnapshot()
        pinBaseline = originalPins
        lateinit var camera: CameraPosition
        var listeners: List<Int> = emptyList()
        compose.runOnUiThread {
            map.cameraPosition = CameraPosition.Builder().target(LatLng(-34.925, 138.625))
                .zoom(13.0).bearing(11.0).tilt(15.0).build()
            camera = map.cameraPosition
            listeners = listenerCounts(view)
            assertThat(listeners.all { it > 0 }).isTrue()
        }
        recordNative("pin-fixture-camera", map, view)
        var dropX = 0f
        var dropY = 0f
        compose.runOnUiThread {
            dropX = view.width * 0.72f
            dropY = view.height * 0.62f
            // Prior failed runs may leave another saved pin at the camera center.
            // Isolate this fixture without deleting or altering any existing row.
            check(map.queryRenderedFeatures(RectF(dropX - 40f, dropY - 40f, dropX + 40f, dropY + 40f),
                PINS_POINTS_LAYER).isEmpty()) { "component=native-proof seam=pin-fixture-isolation class=not-joined" }
        }
        longPress(view, dropX, dropY)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.map_drop_pin_title)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.pin_name_label)))
            .performTextReplacement(pinName)
        compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.map_pin_saved, pinName)).fetchSemanticsNodes().isNotEmpty()
        }
        val clicks = CopyOnWriteArrayList<PinClickReceipt>()
        val observer = MapLibreMap.OnMapClickListener { latLng ->
            val hits = map.queryRenderedFeatures(map.projection.toScreenLocation(latLng), PINS_POINTS_LAYER)
            clicks += PinClickReceipt(hits.firstOrNull()?.getStringProperty("id"), hits.size)
            false
        }
        ownedRestorations += { compose.runOnUiThread { map.removeOnMapClickListener(observer) } }
        try {
            val fixturePin = readFixturePin(pinName)
            compose.runOnUiThread {
                map.addOnMapClickListener(observer)
                val detectorField = MapView::class.java.getDeclaredField("mapGestureDetector").apply { isAccessible = true }
                val detector = detectorField.get(view)
                val clickField = detector.javaClass.getDeclaredField("onMapClickListenerList").apply { isAccessible = true }
                val listenerList = clickField.get(detector)
                listenerList.javaClass.getMethod("remove", Any::class.java).invoke(listenerList, observer)
                listenerList.javaClass.getMethod("add", Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(listenerList, 0, observer)
                check(listenerList.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(listenerList, 0) === observer)
                listeners = listenerCounts(view)
            }
            // The exact saved fixture must render BEFORE adoption; do not attribute
            // a missing Room/viewport baseline to a later style replacement.
            awaitRenderedLayer(map, view, PINS_POINTS_LAYER, fixturePin)
            assertPinDetailsByTap(map, view, fixturePin, pinName, clicks)
            dismissPinDetails(pinName)
            recordNative("pin-baseline-positive", map, view, fixturePin)
            for ((index, suffix) in listOf("A", "B", "A").withIndex()) {
                val version = "$fixtureId-$suffix"
                val directory = store.packDir(version).apply { mkdirs() }
                if (directory !in dirs) dirs += directory
                val tiles = File(directory, "tiles.mbtiles")
                context.assets.open("maptile/adelaide-test-tiles.mbtiles").use { input ->
                    tiles.outputStream().use { input.copyTo(it) }
                }
                val pack = InstalledPack(version, "AU-SA", Instant.fromEpochMilliseconds(0), tiles.length(),
                    "tiles.mbtiles", null, null, "a".repeat(64))
                store.writeCurrent(pack)
                installed.value = pack // the real publisher consumed by the real MapScreen
                awaitStyleUri(map, tiles.canonicalPath)
                awaitRenderedLayer(map, view, PINS_POINTS_LAYER, fixturePin)
                compose.runOnUiThread {
                    assertThat(findMap(compose.activity.window.decorView)).isSameInstanceAs(view)
                    assertThat(map.cameraPosition).isEqualTo(camera)
                    assertThat(listenerCounts(view)).isEqualTo(listeners)
                }
                assertPinDetailsByTap(map, view, fixturePin, pinName, clicks)
                dismissPinDetails(pinName)
                assertThat(pinSnapshot().filterKeys { it != fixturePin.id } == originalPins).isTrue()
                assertThat(prefsStore.preferences.first() == prefs).isTrue()
                recordNative("pin-adopt-${index + 1}-$suffix-positive", map, view, fixturePin)
            }
            store.clearCurrent()
            installed.value = null
            awaitStyleUri(map, File(context.filesDir, "adelaide-test-tiles.mbtiles").canonicalPath)
            awaitRenderedLayer(map, view, PINS_POINTS_LAYER, fixturePin)
            compose.runOnUiThread {
                assertThat(findMap(compose.activity.window.decorView)).isSameInstanceAs(view)
                assertThat(map.cameraPosition).isEqualTo(camera)
                assertThat(listenerCounts(view)).isEqualTo(listeners)
            }
            assertThat(prefsStore.preferences.first() == prefs).isTrue()
            assertThat(pinSnapshot().filterKeys { it != fixturePin.id } == originalPins).isTrue()
            assertPinDetailsByTap(map, view, fixturePin, pinName, clicks)
            recordNative("pin-fallback-positive", map, view, fixturePin)
            compose.onNodeWithText(context.getString(R.string.pin_delete)).performClick()
            compose.waitUntil(10_000) { pinSnapshot() == originalPins }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(pinName).fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            recordNative("pin-delete-room-ready", map, view, fixturePin)
            // Room/Compose completion is not acknowledgement by the native source/frame.
            // Keep the original 10s bound and require source, paint and canonical zero.
            try {
                compose.waitUntil(10_000) {
                    var absent = false
                    compose.runOnUiThread {
                        val style = map.style
                        val source = style?.getSourceAs<GeoJsonSource>(PINS_SOURCE_ID)
                        val pixel = map.projection.toScreenLocation(LatLng(fixturePin.lat, fixturePin.lon))
                        absent = source != null && style?.getLayer(PINS_POINTS_LAYER) != null &&
                            source.querySourceFeatures(null).none { it.getStringProperty("id") == fixturePin.id.toString() } &&
                            map.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                                PINS_POINTS_LAYER).none { it.getStringProperty("id") == fixturePin.id.toString() } &&
                            map.queryRenderedFeatures(pixel, PINS_POINTS_LAYER).isEmpty()
                    }
                    absent
                }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                recordNative("pin-delete-native-timeout", map, view, fixturePin)
                throw error
            }
            recordNative("pin-delete-native-ready", map, view, fixturePin)
            // A true deleted-pin negative: the exact persisted point no longer hits
            // a pin, a real tap cannot reopen its details, and all earlier rows survive.
            var removedX = 0f
            var removedY = 0f
            compose.runOnUiThread {
                val pixel = map.projection.toScreenLocation(LatLng(fixturePin.lat, fixturePin.lon))
                assertThat(map.queryRenderedFeatures(pixel, PINS_POINTS_LAYER)).isEmpty()
                removedX = pixel.x
                removedY = pixel.y
            }
            val beforeClick = clicks.size
            tap(map, view, removedX, removedY, beforeClick + 1) { clicks.size }
            assertThat(clicks.last()).isEqualTo(PinClickReceipt(null, 0))
            compose.waitForIdle()
            compose.onNodeWithText(pinName).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.pin_delete)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.map_drop_pin_title)).assertDoesNotExist()
            assertThat(pinSnapshot() == originalPins).isTrue()
            assertThat(prefsStore.preferences.first() == prefs).isTrue()
            compose.runOnUiThread {
                assertThat(findMap(compose.activity.window.decorView)).isSameInstanceAs(view)
                assertThat(map.cameraPosition).isEqualTo(camera)
                assertThat(listenerCounts(view)).isEqualTo(listeners)
            }
            recordNative("pin-delete-physical-negative", map, view, fixturePin)
        } finally {
            restoreOwnedFixtures()
        }
    }

    @Test fun cachePathKillIsVisibleAndPersistentThenSameScreenRetryAndCorruptCopyRecover(): Unit = runBlocking {
        awaitRenderedMap()
        openExplore()
        val cache = File(context.filesDir, "adelaide-test-tiles.mbtiles")
        val backup = cache.readBytes()
        val sentinel = File(context.cacheDir, "map-sentinel-${System.nanoTime()}")
        ownedRestorations += { sentinel.delete(); Unit }
        ownedRestorations += {
            if (Files.isSymbolicLink(cache.toPath())) check(cache.delete())
            cache.writeBytes(backup)
        }
        sentinel.writeText("preserved")
        val witness = File(context.noBackupFilesDir, "places-health.json")
        val before = failures(witness)
        try {
            check(cache.delete())
            Files.createSymbolicLink(cache.toPath(), sentinel.toPath())
            openMap()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(context.getString(R.string.map_load_error)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.map_load_error)).assertIsDisplayed()
            compose.waitUntil(10_000) { failures(witness) > before }
            assertThat(JSONObject(witness.readText()).getJSONObject("MAP_ASSETS").getString("last_error"))
                .isEqualTo("seam_failed")
            assertThat(sentinel.readText()).isEqualTo("preserved")
            val retainedView = requireNotNull(findMap(compose.activity.window.decorView))
            recordNative("cache-kill-negative", awaitNativeMap(retainedView), retainedView)
            check(cache.delete()) // remove only this controlled fixture link
            val retryAt = System.currentTimeMillis()
            compose.onNodeWithText(context.getString(R.string.explore_retry)).performClick()
            val retried = awaitRenderedMap()
            compose.runOnUiThread {
                assertThat(findMap(compose.activity.window.decorView)).isSameInstanceAs(retainedView)
            }
            compose.onNodeWithText(context.getString(R.string.map_load_error)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.map_loading)).assertDoesNotExist()
            awaitSuccess(witness, "MAP_ASSETS", retryAt)
            awaitSuccess(witness, "MAP_STYLE", retryAt)
            recordNative("cache-retry-positive", retried, retainedView)
            openExplore()
            cache.writeText("controlled partial SQLite")
            openMap()
            val repaired = awaitRenderedMap()
            assertThat(cache.readBytes()).isEqualTo(backup)
            recordNative("cache-corrupt-repair-positive", repaired, requireNotNull(findMap(compose.activity.window.decorView)))
        } finally {
            restoreOwnedFixtures()
        }
    }

    @Test fun pendingExplorePlaceCrossesCacheFailureRetryAndBackWithoutLosingSelection(): Unit = runBlocking {
        awaitRenderedMap()
        openExplore()
        val initializer = (context.applicationContext as AusRoadsApp).searchInitializer
        val repository = initializer.javaClass.getDeclaredField("searchRepository")
            .apply { isAccessible = true }.get(initializer) as FtsSearchRepository
        val target = repository.discover(DiscoveryRequest(latitude = -34.92, longitude = 138.62,
            radiusKm = 20, query = "33 Chapel")).single()
        val beforePrefs = DataStoreExplorationStore(context).preferences.first()
        compose.onNode(hasSetTextAction()).performTextReplacement(target.name)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.explore_on_map)).fetchSemanticsNodes().isNotEmpty()
        }
        val cache = File(context.filesDir, "adelaide-test-tiles.mbtiles")
        val backup = cache.readBytes()
        val sentinel = File(context.cacheDir, "selection-sentinel-${System.nanoTime()}")
        ownedRestorations += { sentinel.delete(); Unit }
        ownedRestorations += {
            if (Files.isSymbolicLink(cache.toPath())) check(cache.delete())
            cache.writeBytes(backup)
        }
        sentinel.writeText("preserved")
        try {
            check(cache.delete())
            Files.createSymbolicLink(cache.toPath(), sentinel.toPath())
            // The real IME clips this lazy card's prefetched action. Existence is
            // not reachable input; expose it before the unchanged cache-failure click.
            val onMap = compose.onAllNodesWithText(context.getString(R.string.explore_on_map)).onFirst()
                .performScrollTo().assertIsDisplayed()
            val bounds = onMap.fetchSemanticsNode().boundsInRoot
            emitNativeStage(JSONObject().put("case", testName.methodName).put("stage", "selection-on-map-target")
                .put("target_width_px", bounds.width.toDouble()).put("target_height_px", bounds.height.toDouble()))
            onMap.performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.map_load_error)).fetchSemanticsNodes().isNotEmpty()
            }
            check(cache.delete())
            val retryAt = System.currentTimeMillis()
            compose.onNodeWithText(context.getString(R.string.explore_retry)).performClick()
            val map = awaitRenderedMap()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(target.name).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(target.name).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.route_start_navigation)).assertDoesNotExist()
            val view = findMap(compose.activity.window.decorView)!!
            awaitRenderedLayer(map, view, "selected-place-dot")
            var readiness = JSONObject()
            var recordedFirst = false
            try {
                compose.waitUntil(10_000) {
                    var selected = false
                    compose.runOnUiThread {
                        val camera = map.cameraPosition
                        val center = camera.target
                        val cameraReady = center != null &&
                            kotlin.math.abs(center.latitude - target.latitude) < 0.00001 &&
                            kotlin.math.abs(center.longitude - target.longitude) < 0.00001 &&
                            kotlin.math.abs(camera.zoom - 15.0) < 0.000001
                        val features = map.queryRenderedFeatures(
                            RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), "selected-place-dot")
                        // Camera completion does not acknowledge asynchronous tile layout.
                        // Join the ORIGINAL every-copy precision and canonical hit inside
                        // the EXISTING camera deadline; never widen either tolerance.
                        val precise = features.isNotEmpty() && features.all { feature ->
                            val point = feature.geometry() as? Point
                            feature.getStringProperty(SELECTED_PLACE_KEY) == target.placeKey() && point != null &&
                                kotlin.math.abs(point.latitude() - target.latitude) <= 0.000001 &&
                                kotlin.math.abs(point.longitude() - target.longitude) <= 0.000001
                        }
                        val pixel = map.projection.toScreenLocation(LatLng(target.latitude, target.longitude))
                        val canonicalHit = map.queryRenderedFeatures(pixel, "selected-place-dot")
                            .any { it.getStringProperty(SELECTED_PLACE_KEY) == target.placeKey() }
                        selected = cameraReady && precise && canonicalHit
                        readiness = JSONObject().put("case", testName.methodName)
                            .put("frames", frameCount.get()).put("copies", features.size)
                            .put("camera_ready", cameraReady).put("all_copies_precise", precise)
                            .put("canonical_hit", canonicalHit)
                    }
                    if (!recordedFirst) {
                        emitNativeStage(readiness.put("stage", "selection-readiness-first"))
                        recordedFirst = true
                    }
                    selected
                }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                emitNativeStage(readiness.put("stage", "selection-readiness-timeout"))
                recordNative("selection-geometry-timeout", map, view)
                throw error
            }
            emitNativeStage(readiness.put("stage", "selection-readiness-ready"))
            // Fixed metadata only: do not serialize feature properties, private rows,
            // coordinates, source paths or an unrelated user's map screenshot.
            recordNative("selection-geometry-observed", map, view)
            compose.runOnUiThread {
                val rendered = map.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                    "selected-place-dot")
                // A rendered GeoJSON feature can be returned once per visible tile. Require
                // every painted copy to represent the selected place, not an assumed count.
                assertThat(rendered).isNotEmpty()
                rendered.forEach { feature ->
                    assertThat(feature.getStringProperty(SELECTED_PLACE_KEY)).isEqualTo(target.placeKey())
                    val point = feature.geometry() as org.maplibre.geojson.Point
                    assertThat(point.latitude()).isWithin(0.000001).of(target.latitude)
                    assertThat(point.longitude()).isWithin(0.000001).of(target.longitude)
                }
                val pixel = map.projection.toScreenLocation(LatLng(target.latitude, target.longitude))
                assertThat(map.queryRenderedFeatures(pixel, "selected-place-dot")
                    .map { it.getStringProperty(SELECTED_PLACE_KEY) }).contains(target.placeKey())
            }
            val witness = File(context.noBackupFilesDir, "places-health.json")
            awaitSuccess(witness, "MAP_ASSETS", retryAt)
            awaitSuccess(witness, "MAP_STYLE", retryAt)
            assertThat(sentinel.readText()).isEqualTo("preserved")
            assertThat(DataStoreExplorationStore(context).preferences.first() == beforePrefs).isTrue()
            recordNative("selection-retry-positive", map, view)
            Espresso.pressBack()
            Espresso.pressBack()
            compose.onNodeWithText(context.getString(R.string.explore_discover)).assertExists()
            compose.onNode(hasSetTextAction()).assertTextEquals(target.name)
            emitNativeStage(JSONObject().put("case", testName.methodName).put("stage", "selection-back-positive"))
        } finally {
            restoreOwnedFixtures()
        }
    }

    @Test fun staleAndMissingNativePinHitsAreConsumedWithoutOpeningEditor(): Unit = runBlocking {
        val originalPrefs = DataStoreExplorationStore(context).preferences.first()
        val map = awaitRenderedMap()
        proveTapRestorationFailureWitness(map)
        val view = requireNotNull(findMap(compose.activity.window.decorView))
        val originalPins = pinSnapshot()
        check(Long.MIN_VALUE !in originalPins)
        val originalFeatures = persistedPinFeatures()
        lateinit var source: GeoJsonSource
        lateinit var appClick: MapLibreMap.OnMapClickListener
        lateinit var camera: CameraPosition
        lateinit var canonical: LatLng
        var x = 0f
        var y = 0f
        var listeners: List<Int> = emptyList()
        compose.waitUntil(10_000) {
            var ready = false
            compose.runOnUiThread {
                ready = map.style?.getSource(PINS_SOURCE_ID) != null && listenerCounts(view).all { it > 0 }
            }
            ready
        }
        compose.runOnUiThread {
            source = requireNotNull(map.style?.getSourceAs<GeoJsonSource>(PINS_SOURCE_ID))
            val detector = MapView::class.java.getDeclaredField("mapGestureDetector")
                .apply { isAccessible = true }.get(view)
            val clicks = detector.javaClass.getDeclaredField("onMapClickListenerList")
                .apply { isAccessible = true }.get(detector) as Collection<*>
            // This is the actual registered MapScreen consumer, not a test selection function.
            appClick = clicks.single() as MapLibreMap.OnMapClickListener
            camera = map.cameraPosition
            x = view.width * 0.72f
            y = view.height * 0.62f
            canonical = map.projection.fromScreenLocation(android.graphics.PointF(x, y))
        }
        ownedRestorations += {
            compose.runOnUiThread { source.setGeoJson(originalFeatures) }
            assertThat(pinSnapshot() == originalPins).isTrue()
        }
        val clicks = CopyOnWriteArrayList<String?>()
        val observer = MapLibreMap.OnMapClickListener { coordinate ->
            val first = map.queryRenderedFeatures(map.projection.toScreenLocation(coordinate), PINS_POINTS_LAYER).firstOrNull()
            clicks += first?.properties()?.get("fixture_kind")?.asString
            false
        }
        ownedRestorations += { compose.runOnUiThread { map.removeOnMapClickListener(observer) } }
        try {
            compose.runOnUiThread {
                map.addOnMapClickListener(observer)
                val detector = MapView::class.java.getDeclaredField("mapGestureDetector")
                    .apply { isAccessible = true }.get(view)
                val list = detector.javaClass.getDeclaredField("onMapClickListenerList")
                    .apply { isAccessible = true }.get(detector)
                list.javaClass.getMethod("remove", Any::class.java).invoke(list, observer)
                list.javaClass.getMethod("add", Int::class.javaPrimitiveType, Any::class.java).invoke(list, 0, observer)
                check(list.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(list, 0) === observer)
                listeners = listenerCounts(view)
            }
            for (kind in listOf("deleted", "missing", "invalid", "null", "non-scalar")) {
                val feature = Feature.fromGeometry(Point.fromLngLat(canonical.longitude, canonical.latitude)).apply {
                    addStringProperty("fixture_kind", kind)
                    when (kind) {
                        "deleted" -> addStringProperty("id", Long.MIN_VALUE.toString())
                        "invalid" -> addStringProperty("id", "not-an-id")
                        "null" -> addProperty("id", com.google.gson.JsonNull.INSTANCE)
                        "non-scalar" -> addProperty("id", com.google.gson.JsonObject())
                        else -> Unit // deliberately absent ID.
                    }
                }
                compose.runOnUiThread { source.setGeoJson(FeatureCollection.fromFeature(feature)) }
                compose.waitUntil(10_000) {
                    var ready = false
                    compose.runOnUiThread {
                        ready = source.querySourceFeatures(null).any { it.properties()?.get("fixture_kind")?.asString == kind } &&
                            map.queryRenderedFeatures(map.projection.toScreenLocation(canonical), PINS_POINTS_LAYER)
                                .firstOrNull()?.properties()?.get("fixture_kind")?.asString == kind
                    }
                    ready
                }
                compose.runOnUiThread { assertThat(appClick.onMapClick(canonical)).isTrue() }
                val beforeClick = clicks.size
                tap(map, view, x, y, beforeClick + 1) { clicks.size }
                assertThat(clicks.last()).isEqualTo(kind)
                compose.waitForIdle()
                compose.onNodeWithText(context.getString(R.string.pin_delete)).assertDoesNotExist()
                compose.onNodeWithText(context.getString(R.string.action_save)).assertDoesNotExist()
                compose.onNodeWithText(context.getString(R.string.map_drop_pin_title)).assertDoesNotExist()
                assertThat(pinSnapshot() == originalPins).isTrue()
                assertThat(DataStoreExplorationStore(context).preferences.first() == originalPrefs).isTrue()
                compose.runOnUiThread {
                    assertThat(map.cameraPosition == camera).isTrue()
                    assertThat(findMap(compose.activity.window.decorView)).isSameInstanceAs(view)
                    assertThat(listenerCounts(view)).isEqualTo(listeners)
                }
                recordNative("stale-$kind-physical-negative", map, view)
            }
        } finally {
            restoreOwnedFixtures()
        }
    }

    @Test fun fatalNativeStyleHasBoundedFailureAndValidNativeStyleStillRecovers(): Unit = runBlocking {
        val map = awaitRenderedMap()
        val view = findMap(compose.activity.window.decorView)!!
        var goodJson = ""
        val failures = CopyOnWriteArrayList<String>()
        val listener = MapView.OnDidFailLoadingMapListener { failures += it }
        compose.runOnUiThread {
            goodJson = requireNotNull(map.style).json
        }
        ownedRestorations += { compose.runOnUiThread { view.removeOnDidFailLoadingMapListener(listener) } }
        compose.runOnUiThread { view.addOnDidFailLoadingMapListener(listener) }
        try {
            val started = SystemClock.elapsedRealtime()
            // Syntactically valid JSON, but never a valid style object. Require the
            // native DID_FAIL witness too: a normal optional tile/image error is not this control.
            val error = runCatching { map.awaitPreparedStyle("[]") }.exceptionOrNull()
            val elapsed = SystemClock.elapsedRealtime() - started
            assertThat(failures).isNotEmpty()
            assertThat(error?.message).isEqualTo("component=map seam=style-load class=not-joined")
            // The configured 15s watchdog uses the actual Android main clock even in
            // Compose instrumentation; this neither raises the watchdog nor claims instant failure.
            assertThat(elapsed).isAtLeast(14_000L)
            assertThat(elapsed).isAtMost(18_000L)
            recordNative("fatal-style-negative", map, view)
            val recovered = map.awaitPreparedStyle(goodJson)
            compose.runOnUiThread { assertThat(recovered.getLayer("place-suburb")).isNotNull() }
            awaitRenderedMap()
            recordNative("fatal-style-recovery-positive", map, view)
        } finally {
            restoreOwnedFixtures()
        }
    }

    private fun awaitSuccess(file: File, seam: String, since: Long) {
        compose.waitUntil(10_000) {
            runCatching { JSONObject(file.readText()).getJSONObject(seam).optLong("last_success_ms") >= since }
                .getOrDefault(false)
        }
    }

    private fun pinSnapshot(): Map<Long, List<String?>> = SQLiteDatabase.openDatabase(
        context.getDatabasePath(PinDatabase.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        database.rawQuery("SELECT * FROM pins ORDER BY id", null).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        (0 until cursor.columnCount).map { index -> cursor.getString(index) })
                }
            }
        }
    }

    private fun persistedPinFeatures(): FeatureCollection = SQLiteDatabase.openDatabase(
        context.getDatabasePath(PinDatabase.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        database.rawQuery("SELECT id, lat, lon, name, color FROM pins ORDER BY id", null).use { cursor ->
            val features = mutableListOf<Feature>()
            while (cursor.moveToNext()) {
                features += Feature.fromGeometry(Point.fromLngLat(cursor.getDouble(2), cursor.getDouble(1))).apply {
                    addStringProperty("id", cursor.getLong(0).toString())
                    addStringProperty("name", cursor.getString(3))
                    addStringProperty("color", cursor.getString(4).ifBlank { PIN_SAVE_COLOR })
                }
            }
            FeatureCollection.fromFeatures(features)
        }
    }

    private data class FixturePin(val id: Long, val lat: Double, val lon: Double)
    private fun readFixturePin(name: String): FixturePin = SQLiteDatabase.openDatabase(
        context.getDatabasePath(PinDatabase.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        database.rawQuery("SELECT id, lat, lon FROM pins WHERE name = ?", arrayOf(name)).use {
            check(it.moveToFirst()) { "component=native-proof seam=pin-persist class=not-joined" }
            FixturePin(it.getLong(0), it.getDouble(1), it.getDouble(2))
        }
    }

    private fun awaitRenderedLayer(map: MapLibreMap, view: MapView, layer: String, pin: FixturePin? = null) {
        try {
            compose.waitUntil(10_000) {
                var rendered = false
                compose.runOnUiThread {
                    val features = map.queryRenderedFeatures(
                        RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), layer)
                    val source = map.style?.getSourceAs<GeoJsonSource>(if (pin != null) PINS_SOURCE_ID else "selected-place")
                    val sourceFeatures = source?.querySourceFeatures(null).orEmpty()
                    rendered = source != null &&
                        sourceFeatures.any { pin == null || it.getStringProperty("id") == pin.id.toString() } &&
                        features.any { pin == null || it.getStringProperty("id") == pin.id.toString() }
                }
                rendered
            }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            var state = ""
            compose.runOnUiThread {
                val style = map.style
                val source = if (pin != null) PINS_SOURCE_ID else "selected-place"
                val data = style?.getSourceAs<GeoJsonSource>(source)?.querySourceFeatures(null)
                state = "layer=${style?.getLayer(layer) != null} source=${style?.getSource(source) != null} " +
                    "source_query_count=${data?.size} width=${view.width} height=${view.height} " +
                    "zoom=${map.cameraPosition.zoom} window_focus=${view.hasWindowFocus()}"
            }
            throw IllegalStateException("component=native-proof seam=rendered-layer class=not-joined $state", error)
        }
    }

    private data class PinClickReceipt(val firstId: String?, val count: Int)

    private fun assertPinDetailsByTap(
        map: MapLibreMap, view: MapView, pin: FixturePin, name: String, clicks: CopyOnWriteArrayList<PinClickReceipt>,
    ) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        var x = 0f
        var y = 0f
        compose.runOnUiThread {
            // Do not follow a potentially misplaced marker. Target its canonical
            // persisted location, and require the raw FIRST hit consumed by the app.
            val pixel = map.projection.toScreenLocation(LatLng(pin.lat, pin.lon))
            val hits = map.queryRenderedFeatures(pixel, PINS_POINTS_LAYER)
            assertThat(hits.firstOrNull()?.getStringProperty("id")).isEqualTo(pin.id.toString())
            x = pixel.x
            y = pixel.y
        }
        val beforeClick = clicks.size
        tap(map, view, x, y, beforeClick + 1) { clicks.size }
        assertThat(clicks.last().firstId).isEqualTo(pin.id.toString())
        assertThat(clicks.last().count).isAtLeast(1)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.pin_delete)).assertIsDisplayed()
    }

    private fun dismissPinDetails(name: String) {
        // The shown sheet owns a focused Dialog root, not the unfocused Activity.
        // Still inject a real Back key and require the actual sheet to disappear.
        Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.pressBack())
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    private fun awaitPinClick(expected: Int, count: () -> Int) {
        try {
            compose.waitUntil(3_000) { count() == expected }
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            emitNativeStage(JSONObject().put("case", testName.methodName).put("stage", "pin-click-timeout")
                .put("expected_callbacks", expected).put("actual_callbacks", count()))
            throw error // Original exact-count predicate and deadline are unchanged.
        }
    }

    private fun observeTapSdk(map: MapLibreMap, record: (String, MotionEvent, Boolean?) -> Unit): () -> Unit {
        val manager = map.gesturesManager
        val detector = manager.standardGestureDetector
        val field = detector.javaClass.superclass.getDeclaredField("listener").apply { isAccessible = true }
        val original = field.get(detector) as StandardGestureDetector.StandardOnGestureListener
        val observer = object : StandardGestureDetector.StandardOnGestureListener by original {
            override fun onDown(event: MotionEvent): Boolean {
                record("pin-sdk-down", event, null)
                return original.onDown(event).also { record("pin-sdk-down-returned", event, it) }
            }
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                record("pin-sdk-tap-up", event, null)
                return original.onSingleTapUp(event).also { record("pin-sdk-tap-up-returned", event, it) }
            }
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                record("pin-sdk-confirmed", event, null)
                return original.onSingleTapConfirmed(event).also { record("pin-sdk-confirmed-returned", event, it) }
            }
            override fun onLongPress(event: MotionEvent) {
                record("pin-sdk-longpress", event, null)
                original.onLongPress(event)
                record("pin-sdk-longpress-returned", event, null)
            }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                record("pin-sdk-doubletap", event, null)
                return original.onDoubleTap(event)
            }
        }
        val restore: () -> Unit = {
            val current = field.get(detector)
            check(current === observer || current === original) {
                "component=native-proof seam=pin-sdk-observer class=owner-changed"
            }
            if (current === observer) manager.setStandardGestureListener(original)
            check(field.get(detector) === original)
        }
        ownedRestorations += { compose.runOnUiThread { restore() } } // BEFORE SDK mutation; idempotent @After fallback.
        manager.setStandardGestureListener(observer)
        check(field.get(detector) === observer)
        return restore
    }

    private fun tap(map: MapLibreMap, view: MapView, x: Float, y: Float, expected: Int, count: () -> Int) {
        val position = IntArray(2)
        compose.runOnUiThread { view.getLocationOnScreen(position) }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var restoreWindow: (() -> Unit)? = null
        val buffered = CopyOnWriteArrayList<JSONObject>()
        val sensorFailures = AtomicInteger()
        fun record(stage: String, event: MotionEvent, accepted: Boolean? = null) {
            // Buffer every contact in this isolated injection window, even when
            // its source/timestamp differs. Diagnostics must never delay or
            // throw before the real consumer, nor hide normalized timestamps.
            runCatching {
                check(buffered.size < 32)
                val now = SystemClock.uptimeMillis()
                val row = JSONObject().put("case", testName.methodName).put("stage", stage)
                    .put("action", event.actionMasked).put("source", event.source)
                    .put("tool", event.getToolType(0)).put("device", event.deviceId)
                    .put("pressure", event.pressure).put("down_ms", event.downTime).put("event_ms", event.eventTime)
                    .put("receiver_ms", now).put("event_age_ms", now - event.eventTime)
                    .put("window_focus", view.hasWindowFocus()).put("shown", view.isShown)
                if (accepted != null) row.put("accepted", accepted)
                buffered += row
            }.onFailure { sensorFailures.incrementAndGet() }
        }
        var restoreSdk: (() -> Unit)? = null
        compose.runOnUiThread {
            restoreSdk = observeTapSdk(map, ::record)
            val window = compose.activity.window
            val original = requireNotNull(window.callback)
            val observer = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    record("pin-touch-received", event)
                    val accepted = original.dispatchTouchEvent(event)
                    record("pin-touch-returned", event, accepted)
                    return accepted // Forward exactly once; no source/timestamp filter.
                }
            }
            val restore = windowRestoration(window, original, observer)
            restoreWindow = restore
            ownedRestorations += { compose.runOnUiThread { restore() } } // Before mutation; same actual Window.
            window.callback = observer
            check(window.callback === observer)
        }
        val time = SystemClock.uptimeMillis() // Trace identity, NOT a synthetic event timestamp.
        var primaryFailure: Throwable? = null
        try {
            check(x.isFinite() && y.isFinite() && x in 0f..view.width.toFloat() && y in 0f..view.height.toFloat()) {
                "component=native-proof seam=pin-input class=invalid-point"
            }
            // UiAutomation pre-fences DOWN after its event has already been dated;
            // c37's actual SDK witness observed LONGPRESS before our intended UP.
            // The OS input command creates one fresh time and consecutive DOWN/UP
            // inside InputManager. It changes the emitter only, never app callbacks.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "input touchscreen tap ${position[0] + x} ${position[1] + y}",
            )).use { stream ->
                check(stream.read() == -1) { "component=native-proof seam=pin-input class=command-output" }
            }
            // Shell completion is NOT delivery proof. Keep both real forwarding
            // witnesses and the ORIGINAL exact-count3s application callback oracle.
            awaitPinClick(expected, count)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            finishTapCleanup(primaryFailure,
                {
                    compose.runOnUiThread {
                        finishTapCleanup(null, requireNotNull(restoreWindow), requireNotNull(restoreSdk))
                    }
                },
                {
                    val trace = "${testName.methodName}-$time"
                    buffered.forEach { emitNativeStage(it.put("trace", trace)) }
                    emitNativeStage(JSONObject().put("case", testName.methodName).put("stage", "pin-touch-sensor")
                        .put("trace", trace).put("emitter", "shell_touchscreen_tap")
                        .put("buffered_rows", buffered.size).put("sensor_failures", sensorFailures.get()))
                    check(sensorFailures.get() == 0) {
                        "component=native-proof seam=pin-input-observer class=not-joined"
                    }
                },
            )
        }
    }

    private fun windowRestoration(window: Window, original: Window.Callback, observer: Window.Callback): () -> Unit = {
        val current = window.callback
        check(current === observer || current === original) {
            "component=native-proof seam=pin-window-observer class=owner-changed"
        }
        if (current === observer) window.callback = original
        check(window.callback === original)
    }

    private fun finishTapCleanup(primary: Throwable?, vararg owners: () -> Unit) {
        val failures = owners.mapNotNull { runCatching(it).exceptionOrNull() }
        val first = primary ?: failures.firstOrNull()
        if (first != null) {
            failures.filter { it !== first }.forEach(first::addSuppressed)
            if (primary == null) throw first
        }
    }

    private fun proveTapRestorationFailureWitness(map: MapLibreMap) {
        compose.runOnUiThread {
            val window = compose.activity.window
            val original = requireNotNull(window.callback)
            val restoreSdk = observeTapSdk(map) { _, _, _ -> }
            val observer = object : Window.Callback by original {}
            val otherOwner = object : Window.Callback by original {}
            val restore = windowRestoration(window, original, observer)
            val primary = IllegalStateException("pin-cleanup-kill-control")
            var sdkRestored = false
            window.callback = otherOwner
            try {
                finishTapCleanup(primary, restore, { restoreSdk(); sdkRestored = true })
                assertThat(sdkRestored).isTrue()
                assertThat(window.callback).isSameInstanceAs(otherOwner)
                assertThat(primary.suppressed.single().message)
                    .isEqualTo("component=native-proof seam=pin-window-observer class=owner-changed")
            } finally {
                check(window.callback === otherOwner)
                window.callback = original // Only our own controlled owner, never a later owner.
            }
            restore() // Idempotent already-restored positive control.
            check(window.callback === original)
        }
    }

    private fun longPress(view: MapView, x: Float = view.width / 2f, y: Float = view.height / 2f) {
        val time = SystemClock.uptimeMillis()
        compose.runOnUiThread {
            MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).let {
                view.dispatchTouchEvent(it); it.recycle()
            }
        }
        Thread.sleep(700) // real native gesture clock
        compose.runOnUiThread {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                x, y, 0).let { view.dispatchTouchEvent(it); it.recycle() }
        }
    }

    private fun openExplore() {
        compose.onAllNodesWithText(context.getString(R.string.nav_explore)).onLast().performClick()
        compose.waitForIdle()
    }
    private fun openMap() {
        compose.onAllNodesWithText(context.getString(R.string.nav_map)).onLast().performClick()
    }
    private fun failures(file: File): Long = runCatching {
        JSONObject(file.readText()).optJSONObject("MAP_ASSETS")?.optLong("failures") ?: 0L
    }.getOrDefault(0)

    private fun awaitStyleUri(map: MapLibreMap, path: String) {
        compose.waitUntil(15_000) {
            var matched = false
            compose.runOnUiThread {
                val style = map.style
                matched = style != null && JSONObject(style.json).getJSONObject("sources")
                    .getJSONObject("aus-roads-tiles").getString("url") == "mbtiles://$path" &&
                    style.getLayer("place-suburb") != null
            }
            matched
        }
        compose.waitForIdle()
    }
    private fun observeNative(view: MapView) {
        if (observedViews.add(view)) {
            view.addOnDidFinishRenderingFrameListener(frameListener)
            view.addOnDidFinishLoadingStyleListener(styleListener)
            view.addOnSourceChangedListener(sourceListener)
            view.addOnDidFailLoadingMapListener(errorListener)
        }
    }

    private fun awaitNativeMap(view: MapView): MapLibreMap {
        var map: MapLibreMap? = null
        compose.runOnUiThread {
            observeNative(view)
            view.getMapAsync { map = it }
        }
        compose.waitUntil(10_000) { map != null }
        return requireNotNull(map)
    }

    private fun awaitRenderedMap(requireInteraction: Boolean = false): MapLibreMap {
        var map: MapLibreMap? = null
        var view: MapView? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread { view = findMap(compose.activity.window.decorView) }
            view != null
        }
        val nativeView = requireNotNull(view)
        compose.runOnUiThread {
            observeNative(nativeView)
            nativeView.getMapAsync { map = it; it.triggerRepaint() }
        }
        val started = SystemClock.elapsedRealtime()
        var interactionSnapshot: JSONObject? = null
        var firstInteractionRecorded = false
        try {
            compose.waitUntil(15_000) {
                var rendered = false
                compose.runOnUiThread {
                    val current = map
                    val geometryReady = current?.style != null && nativeView.width > 0 && nativeView.height > 0 &&
                        nativeView.hasWindowFocus() && nativeView.isAttachedToWindow && nativeView.isShown &&
                        current.queryRenderedFeatures(RectF(0f, 0f, nativeView.width.toFloat(), nativeView.height.toFloat()),
                            "place-suburb", "road-label", "road-minor", "road-secondary", "road-primary", "road-motorway")
                            .isNotEmpty()
                    rendered = geometryReady
                    if (requireInteraction && geometryReady) {
                        // A native base frame can precede Compose's published-map effects.
                        // Join the original listener prerequisite inside this SAME 15s bound;
                        // a permanently withheld publication still times out, never passes.
                        val counts = listenerCounts(nativeView)
                        val pinsReady = current?.style?.getSource(PINS_SOURCE_ID) != null
                        val selectionReady = current?.style?.getSource("selected-place") != null
                        rendered = counts.all { it > 0 } && pinsReady && selectionReady
                        interactionSnapshot = JSONObject().put("case", testName.methodName)
                            .put("view_id", System.identityHashCode(nativeView))
                            .put("at_ms", System.currentTimeMillis())
                            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                            .put("geometry_ready", geometryReady).put("interactive_ready", rendered)
                            .put("click_listeners", counts[0]).put("long_click_listeners", counts[1])
                            .put("pin_source", pinsReady).put("selected_source", selectionReady)
                    }
                }
                if (!firstInteractionRecorded && interactionSnapshot != null) {
                    emitNativeStage(requireNotNull(interactionSnapshot).put("stage", "base-interaction-first"))
                    firstInteractionRecorded = true
                }
                rendered
            }
            interactionSnapshot?.let { emitNativeStage(it.put("stage", "base-interaction-ready")) }
            recordNative("base-geometry-positive", requireNotNull(map), nativeView)
        } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
            interactionSnapshot?.let { emitNativeStage(it.put("stage", "base-interaction-timeout")) }
            map?.let { recordNative("base-geometry-timeout", it, nativeView) }
            throw error
        }
        return requireNotNull(map)
    }

    private fun recordNative(stage: String, map: MapLibreMap, view: MapView, pin: FixturePin? = null) {
        val row = JSONObject().put("case", testName.methodName).put("stage", stage)
            .put("at_ms", System.currentTimeMillis()).put("frames", frameCount.get())
            .put("last_frame_fully", lastFrameFully.get()).put("styles", styleCount.get())
            .put("source_changes", sourceChangeCount.get()).put("native_errors", nativeErrorCount.get())
        compose.runOnUiThread {
            val style = map.style
            val rectangle = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            val painted = map.queryRenderedFeatures(rectangle, PINS_POINTS_LAYER)
            val source = style?.getSourceAs<GeoJsonSource>(PINS_SOURCE_ID)
            val sourceFeatures = source?.querySourceFeatures(null).orEmpty()
            row.put("view_id", System.identityHashCode(view)).put("width", view.width).put("height", view.height)
                .put("focus", view.hasWindowFocus()).put("attached", view.isAttachedToWindow).put("shown", view.isShown)
                .put("zoom", map.cameraPosition.zoom).put("bearing", map.cameraPosition.bearing).put("tilt", map.cameraPosition.tilt)
                .put("base_source", style?.getSource("aus-roads-tiles") != null)
                .put("labels", map.queryRenderedFeatures(rectangle, "place-suburb", "road-label").size)
                .put("roads", map.queryRenderedFeatures(rectangle, "road-minor", "road-secondary", "road-primary", "road-motorway").size)
                .put("pin_source", source != null).put("pin_source_count", sourceFeatures.size).put("painted_pin_count", painted.size)
                .put("selected_source", style?.getSource("selected-place") != null)
            val counts = listenerCounts(view)
            row.put("click_listeners", counts[0]).put("long_click_listeners", counts[1])
            if (pin != null) {
                val id = pin.id.toString()
                val hits = map.queryRenderedFeatures(map.projection.toScreenLocation(LatLng(pin.lat, pin.lon)), PINS_POINTS_LAYER)
                row.put("fixture_source", sourceFeatures.any { it.getStringProperty("id") == id })
                    .put("fixture_paint", painted.any { it.getStringProperty("id") == id })
                    .put("canonical_hits", hits.size).put("canonical_first_fixture", hits.firstOrNull()?.getStringProperty("id") == id)
            }
        }
        val health = File(context.noBackupFilesDir, "places-health.json")
        val metadata = runCatching { JSONObject(health.readText()) }.getOrNull()
        for (seam in listOf("MAP_ASSETS", "MAP_STYLE")) {
            row.put("${seam}_failures", metadata?.optJSONObject(seam)?.optLong("failures"))
                .put("${seam}_last_success_ms", metadata?.optJSONObject(seam)?.optLong("last_success_ms"))
        }
        emitNativeStage(row)
    }

    private fun emitNativeStage(row: JSONObject) {
        // No source JSON, error payload, name, ID, row, preference or filesystem path.
        File(context.cacheDir, "map-native-${testName.methodName}.jsonl").appendText(row.toString() + "\n")
        println("NATIVE_MAP_STAGE $row")
    }
    private fun listenerCounts(view: MapView): List<Int> {
        val field = MapView::class.java.getDeclaredField("mapGestureDetector").apply { isAccessible = true }
        val detector = field.get(view)
        return listOf("onMapClickListenerList", "onMapLongClickListenerList").map { name ->
            val listeners = detector.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(detector)
            (listeners as Collection<*>).size
        }
    }
    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
