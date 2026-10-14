package au.com.ausroads.ui.explore

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.ausroads.MainActivity
import au.com.ausroads.R
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.ExploreCategory
import au.com.ausroads.offline.search.FtsSearchRepository
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import au.com.ausroads.ui.map.CamerasViewModel
import au.com.ausroads.ui.map.PINS_POINTS_LAYER
import au.com.ausroads.ui.map.mapFeatureId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.TestRule
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.geojson.Point

/** Result-kind labels can equal a category label; keep onNode's control uniqueness check. */
internal fun nearbyCategoryChipWithText(label: String): SemanticsMatcher =
    hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

/** Real MainActivity/Hilt/SQLite/DataStore/Compose. Deliberately refuses owner Pixel data. */
@RunWith(AndroidJUnit4::class)
class ExploreJourneyInstrumentedTest {
    val compose = createAndroidComposeRule<MainActivity>()
    private val nativeCasePhases = NativeCasePhaseRule()
    @get:Rule val journey = RuleChain.outerRule(nativeCasePhases).around(compose)
    @get:Rule val testName = TestName()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val displayCapture = NativeDisplayCapture(
        { context.cacheDir }, { testName.methodName }, { compose.waitForIdle() },
    )
    private lateinit var store: DataStoreExplorationStore
    private lateinit var rawStore: DataStore<Preferences>
    private var originalPreferences: Preferences? = null
    private val cameraClicks = AtomicInteger()
    private var cameraClicksBefore = 0
    private var observedCameraMap: MapLibreMap? = null
    private var cameraClickObserver: MapLibreMap.OnMapClickListener? = null
    private val cameraDiagnosticRows = CopyOnWriteArrayList<String>()
    @Volatile private var cameraDiagnosticFailure: Throwable? = null
    private var cameraLongClickObserver: MapLibreMap.OnMapLongClickListener? = null
    private var originalWindowCallback: Window.Callback? = null
    private var observedWindowCallback: Window.Callback? = null
    private var observedWindow: Window? = null
    @Volatile private var tracingCameraContact = false
    private val preferenceDiagnosticRows = CopyOnWriteArrayList<String>()
    private var preferenceModel: ExploreViewModel? = null
    private var restorePreferenceBinding: (() -> Unit)? = null
    @Volatile private var lastObservedPreferences: ExplorationPreferences? = null
    @Volatile private var observedVisitKey: String? = null

    @After
    fun restoreWholePreferences(): Unit = runBlocking {
        nativeCasePhases.phase("after-enter")
        try {
            compose.runOnUiThread {
                restoreObservers(
                    { cameraClickObserver?.let { observedCameraMap?.removeOnMapClickListener(it) } },
                    { cameraLongClickObserver?.let { observedCameraMap?.removeOnMapLongClickListener(it) } },
                    {
                        observedWindowCallback?.let {
                            val window = requireNotNull(observedWindow)
                            check(window.callback === it)
                            window.callback = originalWindowCallback
                        }
                    },
                    { restorePreferenceBinding?.invoke() },
                )
            }
        } finally {
            try {
                originalPreferences?.let { original ->
                    nativeCasePhases.phase("restore-preferences-enter")
                    val restored = rawStore.updateData { original }
                    check(rawStore.data.first() == original)
                    preferenceLifecycle("restored", restored)
                    previousRestoredPreferences = restored
                }
            } finally {
                if (cameraDiagnosticRows.isNotEmpty()) {
                    File(context.cacheDir, "camera-native-tap.jsonl")
                        .appendText(cameraDiagnosticRows.joinToString("\n", postfix = "\n"))
                }
                if (preferenceDiagnosticRows.isNotEmpty()) {
                    File(context.cacheDir, "explore-preferences-${testName.methodName}.jsonl")
                        .appendText(preferenceDiagnosticRows.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    @Before
    fun disposableGuestOnly() = runBlocking {
        check(Build.MODEL.startsWith("sdk_gphone")) {
            "This mutable fixture is emulator-only; never run on owner phone"
        }
        store = DataStoreExplorationStore(context)
        @Suppress("UNCHECKED_CAST")
        val dataStore = DataStoreExplorationStore::class.java.getDeclaredField("dataStore")
            .apply { isAccessible = true }.get(store) as DataStore<Preferences>
        rawStore = dataStore
        originalPreferences = rawStore.data.first() // Preserve unknown keys and every existing row.
        preferenceLifecycle("before-fixture", requireNotNull(originalPreferences))
        store.clearVisits()
        ExploreCategory.entries.forEach { store.setInterest(it, false) }
        store.setPreferNew(true)
        preferenceLifecycle("fixture-ready", rawStore.data.first())
    }

    @Test
    fun coldLaunchExposesExploreWithoutAutofocusAndExplainsOfflineScope() {
        proveCaseFailureWitness()
        proveCaptureJournalFailureWitness()
        assertThat(au.com.ausroads.ui.navigation.AusRoadsDestination.Map.icon.name)
            .isEqualTo("Filled.Map")
        assertThat(au.com.ausroads.ui.navigation.AusRoadsDestination.Pins.icon.name)
            .isEqualTo("Filled.Place")
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose
            .onAllNodesWithText(context.getString(R.string.nav_explore))
            .onLast()
            .assertIsDisplayed()
        openExplore()
        compose
            .onNodeWithText(context.getString(R.string.explore_offline_heading))
            .assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.nav_settings)).onLast().performClick()
        compose
            .onNodeWithText(context.getString(R.string.settings_offline_coverage))
            .assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(R.string.settings_offline_coverage_hint))
            .assertIsDisplayed()
    }

    @Test
    fun mapSearchSqlFailureRetriesUnchangedQueryAndPersistsWitness(): Unit = runBlocking {
        val initializer =
            (context.applicationContext as au.com.ausroads.AusRoadsApp).searchInitializer
        val repositoryField =
            initializer.javaClass.getDeclaredField("searchRepository").apply { isAccessible = true }
        val repository = repositoryField.get(initializer) as FtsSearchRepository
        withTimeout(15_000) { repository.availability.first { it == DiscoveryAvailability.READY } }
        awaitRenderedMap()
        val databaseField =
            repository.javaClass.getDeclaredField("db").apply { isAccessible = true }
        val database = databaseField.get(repository) as SQLiteDatabase
        val epoch = repository.generation.value
        val witness = File(context.noBackupFilesDir, "places-health.json")
        val failuresBefore = runCatching {
            JSONObject(witness.readText()).optJSONObject("MAP_SEARCH_QUERY")?.optLong("failures")
                ?: 0L
        }
            .getOrDefault(0L)
        var renamed = false
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            writable ->
            try {
                writable.execSQL("ALTER TABLE search_meta RENAME COLUMN name TO killed_name")
                renamed = true
                compose.onNode(hasSetTextAction()).performTextReplacement("Adelaide")
                val error = context.getString(au.com.ausroads.feature.search.R.string.search_error)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Retry search").assertIsDisplayed()
                screenshot("map-search-error")
                withTimeout(5_000) {
                    while (
                        !runCatching {
                                JSONObject(witness.readText())
                                    .getJSONObject("MAP_SEARCH_QUERY")
                                    .getLong("failures") > failuresBefore
                            }
                            .getOrDefault(false)
                    ) delay(20)
                }
                writable.execSQL("ALTER TABLE search_meta RENAME COLUMN killed_name TO name")
                renamed = false
                compose.onNodeWithText("Retry search").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText(error).fetchSemanticsNodes().isEmpty() &&
                        compose.onAllNodesWithText("Adelaide").fetchSemanticsNodes().size > 1 &&
                        compose.onAllNodesWithText("Suburb").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Suburb").assertIsDisplayed()
                compose
                    .onAllNodesWithText("from map area", substring = true)
                    .onFirst()
                    .assertIsDisplayed()
                assertThat(repository.generation.value).isEqualTo(epoch)
                withTimeout(5_000) {
                    while (
                        !runCatching {
                                val row =
                                    JSONObject(witness.readText()).getJSONObject("MAP_SEARCH_QUERY")
                                row.optLong("last_success_ms", 0L) >= row.getLong("last_failure_ms")
                            }
                            .getOrDefault(false)
                    ) delay(20)
                }
                assertThat(witness.readText()).doesNotContain("Adelaide")
                screenshot("map-search-recovered")
            } finally {
                if (renamed)
                    writable.execSQL("ALTER TABLE search_meta RENAME COLUMN killed_name TO name")
            }
        }
    }

    @Test
    fun essentialsCatalogWithdrawalShowsFailureThenReadyRestoresSelectedCategory(): Unit = runBlocking {
        val initializer =
            (context.applicationContext as au.com.ausroads.AusRoadsApp).searchInitializer
        val field =
            initializer.javaClass.getDeclaredField("searchRepository").apply { isAccessible = true }
        val repository = field.get(initializer) as FtsSearchRepository
        withTimeout(15_000) { repository.availability.first { it == DiscoveryAvailability.READY } }
        val databaseField = repository.javaClass.getDeclaredField("db").apply { isAccessible = true }
        val path = (databaseField.get(repository) as SQLiteDatabase).path
        val cache = File(path)
        check(cache.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile &&
            cache.name.startsWith("catalog-sa-")) { "Only fault the emulator public catalog cache" }
        val originalCatalog = cache.readBytes()
        val epoch = repository.generation.value
        openExplore()
        compose.onNodeWithText(context.getString(R.string.explore_essentials)).performClick()
        assertThat(context.getString(R.string.nearby_cat_hospital)).isEqualTo("Hospitals & clinics")
        assertThat(context.getString(R.string.nearby_nearest_hospital)).isEqualTo("Nearest hospital or clinic")
        compose.onNodeWithText(context.getString(R.string.nearby_emergency_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.nearby_medical_limits)).assertIsDisplayed()
        val fuel = context.getString(R.string.nearby_cat_fuel)
        val fuelChip = nearbyCategoryChipWithText(fuel)
        proveCategoryControlMatcher(fuelChip)
        compose.onNode(fuelChip).performClick()
        val heading = context.getString(R.string.nearby_results_title)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty()
        }
        val labelMatches = compose.onAllNodesWithText(fuel).fetchSemanticsNodes().size
        assertThat(labelMatches).isGreaterThan(1) // Real control plus real returned result-kind labels.
        assertCategorySelectionRefuses(hasText(fuel), "found '$labelMatches'")
        compose.onNode(fuelChip).assertIsSelected()
        nativeCasePhases.phase("category-label-ambiguity-refusal-proven")
        val categoryError = context.getString(R.string.nearby_category_error)
        val emergencyError = context.getString(R.string.nearby_lookup_error)
        try {
            repository.close() // real repository lifecycle, never writing its state-flow witness
            cache.writeText("damaged public catalog fixture")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(categoryError).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(emergencyError).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(heading).assertDoesNotExist()
            compose.onNode(fuelChip).assertIsSelected()
            // Recovery is the selected category's visible Retry, never a test repository.open().
            compose.onAllNodesWithText(context.getString(R.string.map_pack_retry))
                .onLast().performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(categoryError).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(emergencyError).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(fuelChip).assertIsSelected()
            assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
            assertThat(repository.generation.value).isGreaterThan(epoch)
            assertThat(cache.readBytes()).isEqualTo(originalCatalog)
            screenshot("essentials-category-retry-recovered")
            // Emergency Retry owns the same revalidation and retains the selected category too.
            repository.close()
            cache.writeText("damaged public catalog fixture")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(emergencyError).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(categoryError).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText(context.getString(R.string.map_pack_retry))
                .onFirst().performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(10_000) {
                repository.availability.value == DiscoveryAvailability.READY &&
                    compose.onAllNodesWithText(emergencyError).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(categoryError).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(fuelChip).assertIsSelected()
            assertThat(cache.readBytes()).isEqualTo(originalCatalog)
        } finally {
            repository.close()
            cache.writeBytes(originalCatalog)
            initializer.initialize() // Teardown only; cannot satisfy either UI recovery assertion.
        }
    }

    private fun proveCategoryControlMatcher(fuelChip: SemanticsMatcher) {
        compose.onAllNodes(fuelChip).assertCountEquals(1)
        compose.onNode(fuelChip).assertIsNotSelected()
        assertCategorySelectionRefuses(fuelChip, "(Selected = 'true')")
        val missing = nearbyCategoryChipWithText("__ausroads_absent_category_control__")
        compose.onAllNodes(missing).assertCountEquals(0)
        assertCategorySelectionRefuses(missing, "could not find any node that satisfies")
        val controls = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        val matches = compose.onAllNodes(controls).fetchSemanticsNodes().size
        assertThat(matches).isGreaterThan(1)
        assertCategorySelectionRefuses(controls, "found '$matches'")
        nativeCasePhases.phase("category-control-refusals-proven")
    }

    private fun assertCategorySelectionRefuses(selector: SemanticsMatcher, expected: String) {
        val failure = try {
            compose.onNode(selector).assertIsSelected()
            null
        } catch (caught: AssertionError) {
            caught
        }
        assertThat(failure).isNotNull()
        assertThat(requireNotNull(failure).message).contains(expected)
    }

    @Test
    fun cameraDrawingAndRealTapsRestoreAfterForegroundSearchClear() {
        val map = awaitRenderedMap()
        var id: String? = null
        var point: Point? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                val view = requireNotNull(findMap(compose.activity.window.decorView))
                val feature =
                    map.queryRenderedFeatures(
                            RectF(0f, view.height * 0.4f, view.width.toFloat(), view.height * 0.7f),
                            "cameras-points",
                        )
                        .firstOrNull()
                id = feature?.getStringProperty("id")
                point = feature?.geometry() as? Point
            }
            id != null && point != null
        }
        val original = requireNotNull(point)
        tapCamera(map, original, requireNotNull(id))
        val title = context.getString(R.string.map_camera_title)
        awaitCameraSheet(map, original, requireNotNull(id), title)
        dismissDialogSheet(title)
        compose.onNode(hasSetTextAction()).performTextReplacement("Adelaide")
        compose.waitUntil(5_000) {
            var hidden = false
            compose.runOnUiThread {
                hidden =
                    map.style?.getLayer("cameras-points") == null &&
                        map.style?.getSource("speed-cameras") == null
            }
            hidden
        }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose
            .onNodeWithContentDescription(
                context.getString(au.com.ausroads.feature.search.R.string.clear_icon_desc)
            )
            .performClick()
        Espresso.closeSoftKeyboard()
        compose.waitUntil(10_000) {
            var restored = false
            compose.runOnUiThread {
                val view = requireNotNull(findMap(compose.activity.window.decorView))
                restored =
                    map.queryRenderedFeatures(
                            RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                            "cameras-points",
                        )
                        .any { it.getStringProperty("id") == id }
            }
            restored
        }
        tapCamera(map, original, requireNotNull(id))
        awaitCameraSheet(map, original, requireNotNull(id), title)
        compose.onNodeWithText(title).assertIsDisplayed()
        dismissDialogSheet(title)
    }

    private fun dismissDialogSheet(title: String) {
        // A modal sheet owns a focused Dialog, not the Activity root.
        // Send real Back to that owner and verify dismissal without finishing the app.
        Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.pressBack())
        compose.waitUntil(5_000) {
            var focused = false
            compose.runOnUiThread { focused = compose.activity.window.decorView.hasWindowFocus() }
            focused && compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        compose.runOnUiThread {
            check(!compose.activity.isFinishing && !compose.activity.isDestroyed) {
                "component=sheet-proof seam=sheet-back class=activity-finished"
            }
        }
    }

    private fun tapCamera(map: MapLibreMap, point: Point, expected: String) {
        compose.onNodeWithText(context.getString(R.string.map_camera_title)).assertDoesNotExist()
        cameraClicksBefore = cameraClicks.get()
        var x = 0f
        var y = 0f
        compose.runOnUiThread {
            val view = requireNotNull(findMap(compose.activity.window.decorView))
            cameraClickObserver?.let { observedCameraMap?.removeOnMapClickListener(it) }
            val observer = MapLibreMap.OnMapClickListener { coordinate ->
                cameraClicks.incrementAndGet()
                val received = LatLng(coordinate.latitude, coordinate.longitude)
                // Observe AFTER every real consumer returns. Never throw or do file I/O
                // from the nonconsuming SDK witness ahead of the application listener.
                view.post {
                    try {
                        cameraDiagnostic(map, received, expected, "after-raw-sdk-click")
                    } catch (failure: Exception) {
                        cameraDiagnosticFailure = failure
                        println("NATIVE_CAMERA_DIAGNOSTIC seam=observer class=${failure.javaClass.simpleName}")
                    }
                }
                false // Never consume or invoke/replace the real application consumer.
            }
            cameraLongClickObserver?.let { observedCameraMap?.removeOnMapLongClickListener(it) }
            observedCameraMap = map
            cameraClickObserver = observer
            map.addOnMapClickListener(observer)
            installCameraContactWitness(map)
            val detector = MapView::class.java.getDeclaredField("mapGestureDetector")
                .apply { isAccessible = true }.get(view)
            val listeners = detector.javaClass.getDeclaredField("onMapClickListenerList")
                .apply { isAccessible = true }.get(detector)
            listeners.javaClass.getMethod("remove", Any::class.java).invoke(listeners, observer)
            listeners.javaClass.getMethod("add", Int::class.javaPrimitiveType, Any::class.java).invoke(listeners, 0, observer)
            check(listeners.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(listeners, 0) === observer)
            cameraDiagnostic(map, LatLng(point.latitude(), point.longitude()), expected, "before-physical-tap")
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            val screen =
                map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
            x = position[0] + screen.x
            y = position[1] + screen.y
        }
        // One public TEST-only mechanism control: same computed x/y and five-second
        // outcome, without UiAutomation's pre-DOWN transaction fence. Shell owns the
        // timestamp/source4098/finger/device/UP-pressure metadata; NOT an identical event.
        // input tap still uses synchronous WAIT_FOR_FINISH. A positive is not an
        // exclusive timing-cause finding; root receipt clocks/SDK callbacks remain evidence.
        tracingCameraContact = true
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        cameraDiagnosticRows.add(JSONObject().put("stage", "public-shell-tap-begin")
            .put("at_ms", SystemClock.uptimeMillis()).put("longpress_timeout_ms", ViewConfiguration.getLongPressTimeout())
            .put("expected_source", 4098).put("timestamp_owner", "shell").toString())
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap $x $y")).use {
            check(it.readBytes().toString(Charsets.UTF_8).isBlank())
        }
        cameraDiagnosticRows.add(JSONObject().put("stage", "public-shell-tap-return")
            .put("at_ms", SystemClock.uptimeMillis()).toString())
    }

    private fun awaitCameraSheet(map: MapLibreMap, point: Point, expected: String, title: String) {
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            try {
                compose.runOnUiThread {
                    cameraDiagnostic(map, LatLng(point.latitude(), point.longitude()), expected, "sheet-timeout")
                }
                screenshot("camera-tap-timeout")
            } catch (diagnostic: Exception) {
                failure.addSuppressed(diagnostic)
            }
            throw failure
        }
        check(cameraClicks.get() == cameraClicksBefore + 1) {
            "component=camera-proof seam=physical-tap class=raw-sdk-not-joined"
        }
        tracingCameraContact = false
        check(cameraDiagnosticFailure == null) { "component=camera-proof seam=observer class=diagnostic-failed" }
    }

    private fun installCameraContactWitness(map: MapLibreMap) {
        if (observedWindowCallback == null) {
            val window = compose.activity.window
            val original = requireNotNull(window.callback)
            observedWindow = window // Restoration must not resolve a destroyed ActivityScenario.
            originalWindowCallback = original
            val observer = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (!tracingCameraContact) return original.dispatchTouchEvent(event)
                    val receivedAt = SystemClock.uptimeMillis()
                    val handled = original.dispatchTouchEvent(event) // Always forward exactly once.
                    cameraDiagnosticRows.add(JSONObject().put("stage", "activity-contact-forwarded")
                        .put("action", event.actionMasked).put("source", event.source).put("device", event.deviceId)
                        .put("tool", event.getToolType(0)).put("pressure", event.getPressure(0).toDouble())
                        .put("received_uptime_ms", receivedAt).put("returned_uptime_ms", SystemClock.uptimeMillis())
                        .put("event_ms", event.eventTime).put("down_ms", event.downTime).put("handled", handled).toString())
                    return handled
                }
            }
            observedWindowCallback = observer
            window.callback = observer
        }
        val longObserver = MapLibreMap.OnMapLongClickListener {
            cameraDiagnosticRows.add(JSONObject().put("stage", "raw-sdk-longclick")
                .put("at_ms", SystemClock.uptimeMillis()).toString())
            false // Observe without invoking, replacing or consuming the application callback.
        }
        cameraLongClickObserver = longObserver
        map.addOnMapLongClickListener(longObserver)
        val view = requireNotNull(findMap(compose.activity.window.decorView))
        val detector = MapView::class.java.getDeclaredField("mapGestureDetector").apply { isAccessible = true }.get(view)
        val listeners = detector.javaClass.getDeclaredField("onMapLongClickListenerList")
            .apply { isAccessible = true }.get(detector)
        listeners.javaClass.getMethod("remove", Any::class.java).invoke(listeners, longObserver)
        listeners.javaClass.getMethod("add", Int::class.javaPrimitiveType, Any::class.java).invoke(listeners, 0, longObserver)
    }

    private fun actualCamerasModel(): CamerasViewModel = destinationModels().filterIsInstance<CamerasViewModel>().single()

    private fun destinationModels(): List<ViewModel> {
        fun values(store: ViewModelStore): Collection<ViewModel> {
            @Suppress("UNCHECKED_CAST")
            val models = ViewModelStore::class.java.getDeclaredField("map").apply { isAccessible = true }
                .get(store) as Map<String, ViewModel>
            return models.values.toList()
        }
        val controller = values(compose.activity.viewModelStore)
            .single { it.javaClass.name == "androidx.navigation.NavControllerViewModel" }
        @Suppress("UNCHECKED_CAST")
        val stores = controller.javaClass.getDeclaredField("viewModelStores").apply { isAccessible = true }
            .get(controller) as Map<String, ViewModelStore>
        return stores.values.flatMap(::values)
    }

    private fun cameraDiagnostic(map: MapLibreMap, point: LatLng, expected: String, stage: String) {
        val view = requireNotNull(findMap(compose.activity.window.decorView))
        val pixel = map.projection.toScreenLocation(point)
        val pins = map.queryRenderedFeatures(pixel, PINS_POINTS_LAYER)
        val cameras = map.queryRenderedFeatures(pixel, "cameras-points")
        val detector = MapView::class.java.getDeclaredField("mapGestureDetector").apply { isAccessible = true }.get(view)
        val listeners = detector.javaClass.getDeclaredField("onMapClickListenerList")
            .apply { isAccessible = true }.get(detector) as Collection<*>
        val state = actualCamerasModel().state.value
        val row = JSONObject().put("stage", stage).put("at_ms", SystemClock.elapsedRealtime())
            .put("raw_clicks", cameraClicks.get()).put("click_listeners", listeners.size)
            .put("application_consumers", listeners.count { it !== cameraClickObserver })
            .put("pin_hits", pins.size).put("camera_hits", cameras.size)
            .put("camera_layer_present", map.style?.getLayer("cameras-points") != null)
            .put("first_camera_expected", cameras.firstOrNull()?.getStringProperty("id") == expected)
            .put("expected_in_actual_vm", state.cameras.any { it.mapFeatureId() == expected })
            .put("first_camera_in_actual_vm", state.cameras.any {
                it.mapFeatureId() == cameras.firstOrNull()?.getStringProperty("id")
            })
            .put("selected_nonnull", state.selected != null)
            .put("selected_expected", state.selected?.mapFeatureId() == expected)
            .put("vm_camera_count", state.cameras.size).put("focus", view.hasWindowFocus())
            .put("attached", view.isAttachedToWindow).put("width", view.width).put("height", view.height)
            .put("target_in_view", pixel.x in 0f..view.width.toFloat() && pixel.y in 0f..view.height.toFloat())
        cameraDiagnosticRows.add(row.toString())
        println("NATIVE_CAMERA_DIAGNOSTIC $row") // No coordinate, feature id/name, private row or path.
    }

    @Test
    fun cleanOfflineStartupKeywordVisitAndUndoReachRealPersistence(): Unit = runBlocking {
        openExplore()
        compose.onNode(hasSetTextAction()).performTextReplacement("coffee")
        waitForPlaces()
        startPreferenceTrace()
        assertThat(store.preferences.first().visits).isEmpty()
        compose
            .onAllNodesWithText(context.getString(R.string.explore_mark_visited))
            .onFirst()
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        println("NATIVE_VISIT_STAGE stage=mark-clicked")
        awaitPreference("mark-visit") { it.visits.size == 1 }
        println("NATIVE_VISIT_STAGE stage=mark-persisted")
        compose.waitUntil(10_000) {
            compose
                .onAllNodesWithText(context.getString(R.string.action_undo))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        println("NATIVE_VISIT_STAGE stage=undo-visible")
        compose.onAllNodesWithText(context.getString(R.string.action_undo)).onFirst().performClick()
        println("NATIVE_VISIT_STAGE stage=undo-clicked")
        awaitPreference("undo-visit") { it.visits.isEmpty() }
        println("NATIVE_VISIT_STAGE stage=undo-persisted")
        assertThat(DataStoreExplorationStore(context).preferences.first().visits).isEmpty()
    }

    @Test
    fun interestControlAndNewFirstTogglePersistWithoutImplicitVisits(): Unit = runBlocking {
        openExplore()
        compose
            .onNodeWithContentDescription(context.getString(R.string.explore_preferences))
            .performClick()
        startPreferenceTrace()
        preferenceTargets("nature-before-click")
        compose
            .onAllNodesWithText(context.getString(R.string.explore_nature))
            .onLast()
            .performClick()
        preferenceState("nature-click-return")
        awaitPreference("nature", rendered = {
            compose.onAllNodesWithText(context.getString(R.string.explore_nature))
                .fetchSemanticsNodes().lastOrNull()?.config
                ?.getOrNull(SemanticsProperties.Selected) == true
        }) { ExploreCategory.NATURE in it.interests }
        assertThat(store.preferences.first().visits).isEmpty()
        assertThat(DataStoreExplorationStore(context).preferences.first().interests)
            .contains(ExploreCategory.NATURE)
        val newFirst = context.getString(R.string.explore_new_first)
        compose.onAllNodesWithContentDescription(newFirst).assertCountEquals(1)
        preferenceTargets("new-first-off-before-click")
        compose.onNodeWithContentDescription(newFirst)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOn().performClick()
        preferenceState("new-first-off-click-return")
        awaitPreference("new-first-off", rendered = {
            compose.onAllNodesWithContentDescription(newFirst).fetchSemanticsNodes()
                .singleOrNull()?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
        }) { !it.preferNew }
        compose.onNodeWithContentDescription(newFirst).assertIsOff()
        assertThat(DataStoreExplorationStore(context).preferences.first().preferNew).isFalse()
        preferenceTargets("new-first-on-before-click")
        compose.onNodeWithContentDescription(newFirst).performClick()
        preferenceState("new-first-on-click-return")
        awaitPreference("new-first-on", rendered = {
            compose.onAllNodesWithContentDescription(newFirst).fetchSemanticsNodes()
                .singleOrNull()?.config?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
        }) { it.preferNew }
        compose.onNodeWithContentDescription(newFirst).assertIsOn()
        assertThat(DataStoreExplorationStore(context).preferences.first().preferNew).isTrue()
        assertThat(store.preferences.first().visits).isEmpty()
    }

    @Test
    fun selectionStaysInAppShowsDetailsDoesNotAutorouteAndBackReturnsToDiscovery(): Unit =
        runBlocking {
            nativeCasePhases.phase("selection-body-enter")
            openExplore()
            waitForPlaces()
            nativeCasePhases.phase("selection-on-map-before")
            compose
                .onAllNodesWithText(context.getString(R.string.explore_on_map))
                .onFirst()
                .performClick()
            nativeCasePhases.phase("selection-on-map-return")
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithText(context.getString(R.string.route_directions))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.route_directions)).assertExists()
            compose
                .onNodeWithText(context.getString(R.string.route_start_navigation))
                .assertDoesNotExist()
            assertThat(store.preferences.first().visits).isEmpty()
            nativeCasePhases.phase("selection-details-ready")
            dismissDialogSheet(context.getString(R.string.route_directions))
            nativeCasePhases.phase("selection-dialog-back-return")
            Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                .inRoot(androidx.test.espresso.matcher.RootMatchers.withDecorView(
                    org.hamcrest.Matchers.sameInstance(compose.activity.window.decorView)))
                .perform(androidx.test.espresso.action.ViewActions.pressBack()) // Actual Map owner.
            nativeCasePhases.phase("selection-map-back-return")
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.explore_discover)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.explore_essentials)).assertExists()
        }

    @Test
    fun unreadHistoryNeverShowsDefaultsAndConfirmedClearKeepsCancelAndInterests(): Unit = runBlocking {
        store.setInterest(ExploreCategory.NATURE, true)
        store.setPreferNew(false)
        val before = store.preferences.first()
        rawStore.edit { it[stringSetPreferencesKey("visits")] = setOf("invalid record") }
        val malformed = rawStore.data.first()
        openExplore(waitForSuggestions = false)
        val error = context.getString(R.string.explore_preferences_error)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.explore_preferences)).performClick()
        compose.onNodeWithText(context.getString(R.string.explore_preferences_unknown)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.explore_close_interests)).assertIsDisplayed()
        compose.onAllNodesWithText(error).assertCountEquals(1) // Modal owns the read error, not a clipped duplicate.
        compose.onNodeWithText(context.getString(R.string.explore_visit_count_many, 0)).assertDoesNotExist()
        startPreferenceTrace() // Transparent native clear writer/actual destination VM witness only.
        preferenceState("unread-sheet-open")
        compose.onNodeWithContentDescription(context.getString(R.string.explore_new_first)).assertDoesNotExist()
        compose.onNode(isToggleable()).assertDoesNotExist()
        compose.onNodeWithText("invalid record").assertDoesNotExist()
        val clear = context.getString(R.string.explore_clear_visits)
        val title = context.getString(R.string.explore_clear_confirm_title)
        compose.onNodeWithText(clear).performScrollTo().performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        assertThat(rawStore.data.first()).isEqualTo(malformed)
        compose.onNodeWithText(context.getString(R.string.explore_cancel)).performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertThat(rawStore.data.first()).isEqualTo(malformed)
        screenshot("explore-history-unread-cancel", nativeTail = true)
        compose.onNodeWithText(clear).performClick()
        preferenceState("unread-clear-before-confirm")
        // Prime the ORIGINAL native observer before this write. DataStore1.1.1
        // can dirty-read malformed bytes at the in-flight write's version and
        // then suppress its equal-version commit. An actual initial-value
        // handshake, not merely launch(), establishes observer readiness.
        val observerReady = CompletableDeferred<Unit>()
        val cleared = async(Dispatchers.IO) {
            rawStore.data.onEach { raw ->
                val empty = raw[stringSetPreferencesKey("visits")]?.isEmpty() == true
                preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName)
                    .put("stage", "unread-clear-native-observation")
                    .put("uptime_ms", SystemClock.uptimeMillis()).put("visits_empty", empty)
                    .put("same_malformed", raw == malformed).toString())
                preferenceState("unread-clear-native-read")
                if (!observerReady.isCompleted) {
                    check(raw == malformed && !empty) { "Unread clear observer did not start on original malformed preferences" }
                    preferenceState("unread-clear-observer-armed")
                    observerReady.complete(Unit)
                }
            }.first { raw -> raw[stringSetPreferencesKey("visits")]?.isEmpty() == true }
        }
        try {
            withTimeout(10_000) { observerReady.await() }
            compose.onAllNodesWithText(clear).onLast().performClick()
            preferenceState("unread-clear-confirm-return")
            preferenceState("unread-clear-await-begin")
            // Original raw empty-visits predicate and ten-second post-action
            // deadline; fresh whole-store/read/render preservation checks follow.
            withTimeout(10_000) { cleared.await() }
            preferenceState("unread-clear-await-success")
        } catch (failure: TimeoutCancellationException) {
            preferenceState("unread-clear-await-timeout")
            failure.addSuppressed(IllegalStateException(
                "component=explore-proof seam=unread-clear-native-await class=timeout"))
            try {
                // Separate post-deadline diagnosis BEFORE @After restoration. This
                // snapshot never turns the original timeout into acceptance.
                val fresh = withTimeout(1_000) { rawStore.data.first() }
                preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName)
                    .put("stage", "unread-clear-after-timeout-fresh")
                    .put("uptime_ms", SystemClock.uptimeMillis())
                    .put("visits_empty", fresh[stringSetPreferencesKey("visits")]?.isEmpty() == true)
                    .put("same_malformed", fresh == malformed).toString())
                screenshot("explore-history-clear-timeout")
            } catch (diagnostic: Exception) { failure.addSuppressed(diagnostic) }
            throw failure
        } finally {
            withContext(NonCancellable) { withTimeout(10_000) { cleared.cancelAndJoin() } }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.explore_new_first))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(store.preferences.first()).isEqualTo(before.copy(visits = emptyMap()))
        assertThat(rawStore.data.first().asMap().filterKeys { it.name != "visits" })
            .isEqualTo(malformed.asMap().filterKeys { it.name != "visits" })
        compose.onNodeWithContentDescription(context.getString(R.string.explore_new_first)).assertIsOff()
        compose.onAllNodesWithText(context.getString(R.string.explore_nature)).onLast().assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.explore_visit_count_many, 0))
            .performScrollTo().assertIsDisplayed()
        screenshot("explore-history-confirmed-clear", nativeTail = true)
    }

    @Test
    fun heldNativeSaveShowsProgressAndDisablesEditsUntilPersisted(): Unit = runBlocking {
        openExplore()
        compose.onNodeWithContentDescription(context.getString(R.string.explore_preferences)).performClick()
        val before = store.preferences.first()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.IO) {
            rawStore.updateData { value -> entered.complete(Unit); release.await(); value }
        }
        try {
            withTimeout(5_000) { entered.await() }
            startPreferenceTrace() // Observe the real writer and actual destination model.
            val label = context.getString(R.string.explore_new_first)
            compose.onNodeWithContentDescription(label).performScrollTo().assertIsOn().performClick()
            val saving = context.getString(R.string.explore_saving)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(saving).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText(saving).onLast().assertIsDisplayed()
            compose.onNodeWithContentDescription(label).assertIsOn().assertIsNotEnabled()
            compose.onAllNodesWithText(context.getString(R.string.explore_nature)).onLast().assertIsNotEnabled()
            assertThat(withTimeout(5_000) { store.preferences.first() }).isEqualTo(before)
            screenshot("explore-native-saving")
            compose.onNodeWithText(context.getString(R.string.explore_close_interests))
                .assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(R.string.explore_close_interests)).assertDoesNotExist()
            compose.onAllNodesWithText(saving).assertCountEquals(1) // Destination writer survives modal dismissal.
            assertThat(withTimeout(5_000) { store.preferences.first() }).isEqualTo(before)
        } finally {
            release.complete(Unit)
            blocker.join()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.explore_preferences)).performClick()
        compose.onNodeWithText(context.getString(R.string.explore_close_interests)).assertIsDisplayed()
        // The same ten-second native predicate uses the existing VM/render join:
        // release of our held transaction is not completion of the queued UI writer.
        awaitPreference("held-save", rendered = {
            compose.onAllNodesWithContentDescription(context.getString(R.string.explore_new_first))
                .fetchSemanticsNodes().singleOrNull()?.config
                ?.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
        }) { !it.preferNew }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.explore_saving)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.explore_new_first)).assertIsOff()
        assertThat(store.preferences.first()).isEqualTo(before.copy(preferNew = false))
    }

    @Test
    fun fullVisitHistoryShowsCapacityGuidanceNotStorageRetryAndPreservesEveryVisit(): Unit = runBlocking {
        val records = (1..DataStoreExplorationStore.MAX_VISITS).map { index ->
            val place = SearchResult("History fixture $index", "poi", "amenity=cafe", -35.0, 138.0 + index / 10000.0)
            "${place.placeKey()};${ExploreCategory.COFFEE.name}"
        }.toSet()
        rawStore.edit { it[stringSetPreferencesKey("visits")] = records }
        val before = store.preferences.first()
        assertThat(before.visits).hasSize(DataStoreExplorationStore.MAX_VISITS)
        openExplore()
        compose.onNode(hasSetTextAction()).performClick().performTextReplacement("coffee")
        waitForPlaces()
        startPreferenceTrace()
        lastObservedPreferences = before
        val mark = compose.onAllNodesWithText(context.getString(R.string.explore_mark_visited)).onFirst()
        // Existence can be a prefetched lazy node with ZERO clipped bounds under the IME.
        // Reach the same real action first; neither a View-local zero-centre click nor a
        // direct model/store call establishes admission of visit501.
        mark.performScrollTo().assertIsDisplayed()
        val target = mark.fetchSemanticsNode()
        val targetBounds = target.boundsInWindow
        val windowPosition = IntArray(2)
        val contactsDown = AtomicInteger()
        val contactsUp = AtomicInteger()
        val tracing = java.util.concurrent.atomic.AtomicBoolean(true)
        compose.runOnUiThread {
            val window = compose.activity.window
            val decor = window.decorView
            val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(decor))
            val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val available = (decor.height - keyboard).toFloat()
            check(keyboard > 0 && targetBounds.left >= 0f && targetBounds.top >= 0f &&
                targetBounds.right <= decor.width && targetBounds.bottom <= available &&
                targetBounds.width >= target.size.width && targetBounds.height >= target.size.height &&
                targetBounds.width > 0f && targetBounds.height > 0f &&
                !target.config.contains(SemanticsProperties.Disabled) && decor.hasWindowFocus()) {
                "component=explore-proof seam=capacity-mark-target class=not-fully-reachable"
            }
            decor.getLocationOnScreen(windowPosition)
            check(observedWindowCallback == null)
            val original = requireNotNull(window.callback)
            originalWindowCallback = original
            observedWindow = window
            val observer = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (!tracing.get()) return original.dispatchTouchEvent(event)
                    val received = SystemClock.uptimeMillis()
                    val handled = original.dispatchTouchEvent(event) // Forward exactly once.
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) contactsDown.incrementAndGet()
                    if (event.actionMasked == MotionEvent.ACTION_UP) contactsUp.incrementAndGet()
                    preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName)
                        .put("stage", "capacity-contact-forwarded").put("action", event.actionMasked)
                        .put("source", event.source).put("received_uptime_ms", received)
                        .put("returned_uptime_ms", SystemClock.uptimeMillis()).put("handled", handled).toString())
                    return handled
                }
            }
            observedWindowCallback = observer
            window.callback = observer
            check(window.callback === observer && !compose.activity.isFinishing)
        }
        capacityActionTargets("capacity-before-original-click")
        screenshot("explore-capacity-before-original-click")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "input touchscreen tap ${windowPosition[0] + targetBounds.center.x} ${windowPosition[1] + targetBounds.center.y}",
                ),
            ).use { check(it.read() == -1) }
        } finally {
            tracing.set(false)
        }
        check(contactsDown.get() == 1 && contactsUp.get() == 1) {
            "component=explore-proof seam=capacity-mark-input class=native-contact-not-joined"
        }
        preferenceState("capacity-original-click-return")
        val full = context.getString(R.string.explore_history_full)
        try {
            // Original capacity predicate/deadline; qualified physical input is not a
            // timeout extension or a persistence/model substitution.
            compose.waitUntil(10_000) { compose.onAllNodesWithText(full).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            try {
                capacityActionTargets("capacity-guidance-timeout")
                screenshot("explore-capacity-activation-timeout")
            } catch (diagnostic: Exception) { failure.addSuppressed(diagnostic) }
            throw failure // A later diagnostic/native read must never turn timeout into a pass.
        }
        preferenceState("capacity-guidance-visible")
        val capacityRows = preferenceDiagnosticRows.map { JSONObject(it) }
        check(capacityRows.any { it.optString("stage") == "native-transform-enter" &&
            it.optInt("visit_count", -1) == DataStoreExplorationStore.MAX_VISITS &&
            it.optBoolean("visit_key_known") && !it.optBoolean("target_already_visited", true) } &&
            capacityRows.any { it.optString("stage") == "native-transform-throw-VisitLimitReachedException" } &&
            requireNotNull(preferenceModel).state.value.visitLimitReached) {
            "component=explore-proof seam=capacity-native-refusal class=not-joined"
        }
        compose.onNodeWithText(full).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.explore_write_retry)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.explore_visit_saved)).assertDoesNotExist()
        assertThat(store.preferences.first()).isEqualTo(before)
        assertThat(rawStore.data.first()[stringSetPreferencesKey("visits")]).containsExactlyElementsIn(records)
        val recovery = compose.onNodeWithText(context.getString(R.string.explore_preferences))
        recovery.performScrollTo()
        var keyboardHeight = 0
        var availableBottom = 0f
        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(decor))
            keyboardHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            availableBottom = (decor.height - keyboardHeight).toFloat()
            decor.getLocationOnScreen(windowPosition)
        }
        val bounds = recovery.fetchSemanticsNode().boundsInWindow
        preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName)
            .put("stage", "capacity-ime-recovery").put("keyboard_height", keyboardHeight)
            .put("action_bottom", bounds.bottom.toDouble()).put("available_bottom", availableBottom.toDouble())
            .put("fully_above_keyboard", bounds.bottom <= availableBottom).toString())
        screenshot("explore-history-full") // Capture the actual keyboard/action geometry even on failure.
        assertThat(keyboardHeight).isGreaterThan(0) // Real keyboard is part of this control.
        assertThat(bounds.bottom).isAtMost(availableBottom) // Not just Compose's screen bounds.
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "input touchscreen tap ${windowPosition[0] + bounds.center.x} ${windowPosition[1] + bounds.center.y}",
            ),
        ).use { check(it.read() == -1) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.explore_new_first))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.explore_visit_count_many, DataStoreExplorationStore.MAX_VISITS))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.explore_clear_visits)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.explore_cancel)).performClick()
        assertThat(store.preferences.first()).isEqualTo(before)
    }

    private fun startPreferenceTrace() {
        compose.runOnUiThread {
            val model = destinationModels().filterIsInstance<ExploreViewModel>().single()
            preferenceModel = model
            val field = ExploreViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }
            val original = field.get(model) as DataStoreExplorationStore
            val nativeField = DataStoreExplorationStore::class.java.getDeclaredField("dataStore")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val native = nativeField.get(original) as DataStore<Preferences>
            check(native === rawStore) { "component=explore-proof seam=native-store class=identity-not-joined" }
            val nativeObserver = object : DataStore<Preferences> by native {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    native.updateData { current ->
                        val records = current[stringSetPreferencesKey("visits")]
                        val key = observedVisitKey
                        preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName)
                            .put("stage", "native-transform-enter").put("uptime_ms", SystemClock.uptimeMillis())
                            .put("caller_main", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                            .put("dispatcher_class", coroutineContext[ContinuationInterceptor]?.javaClass?.name)
                            .put("visit_count", records?.size).put("visit_key_known", key != null)
                            .put("target_already_visited", key?.let { target -> records?.any { it.startsWith("$target;") } })
                            .toString())
                        try {
                            transform(current) // Exactly once, inside the REAL native transaction/caller context.
                        } catch (failure: Exception) {
                            preferenceState("native-transform-throw-${failure.javaClass.simpleName}")
                            throw failure
                        }
                    }
            }
            val observer = object : ExplorationStore by original {
                override suspend fun setInterest(category: ExploreCategory, enabled: Boolean) =
                    preferenceWrite("interest") { original.setInterest(category, enabled) }
                override suspend fun setPreferNew(enabled: Boolean) =
                    preferenceWrite("new-first") { original.setPreferNew(enabled) }
                override suspend fun setVisited(result: SearchResult, visited: Boolean) =
                    preferenceWrite(if (visited) "mark-visit" else "undo-visit") {
                        observedVisitKey = result.placeKey() // Comparison only; never serialize the key/result.
                        try { original.setVisited(result, visited) }
                        finally { observedVisitKey = null }
                    }
                override suspend fun clearVisits() =
                    preferenceWrite("clear-visits") { original.clearVisits() }
            }
            // Transparent TEST-only forwarding, never substitute a Flow or persistence effect.
            restorePreferenceBinding = {
                check(field.get(model) === observer && nativeField.get(original) === nativeObserver)
                field.set(model, original)
                nativeField.set(original, native)
                check(field.get(model) === original && nativeField.get(original) === rawStore)
            }
            nativeField.set(original, nativeObserver)
            field.set(model, observer)
            preferenceState("native-store-identity-joined")
        }
    }

    private suspend fun preferenceWrite(kind: String, action: suspend () -> Unit) {
        preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName)
            .put("stage", "$kind-caller").put("uptime_ms", SystemClock.uptimeMillis())
            .put("caller_main", android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            .put("dispatcher_class", coroutineContext[ContinuationInterceptor]?.javaClass?.name).toString())
        preferenceState("$kind-store-enter")
        try {
            action()
            preferenceState("$kind-store-return")
        } catch (failure: Exception) {
            preferenceState("$kind-store-throw-${failure.javaClass.simpleName}")
            throw failure
        }
    }

    private suspend fun awaitPreference(
        stage: String,
        rendered: () -> Boolean = { true },
        predicate: (ExplorationPreferences) -> Boolean,
    ) {
        val model = requireNotNull(preferenceModel)
        check(compose.mainClock.autoAdvance) { "component=explore-proof seam=compose-clock class=not-joined" }
        lastObservedPreferences = null
        preferenceState("$stage-await-begin")
        try {
            // Same native predicate and original ten-second wall-clock deadline.
            // Pump frames AND yield this runBlocking event loop. After the actual
            // writer/VM/render join, take a fresh native snapshot: DataStore 1.1.1
            // explicitly allows a dirty initial read during an in-flight write.
            // No long-lived concurrent first(predicate) collector or leaked child.
            withTimeout(10_000) {
                while (true) {
                    compose.mainClock.advanceTimeByFrame()
                    val state = model.state.value
                    if (state.preferences?.let(predicate) == true && !state.isSaving &&
                        !state.preferencesReadFailed && !state.preferencesWriteFailed && rendered()) {
                        val native = store.preferences.first()
                        if (native != lastObservedPreferences) {
                            lastObservedPreferences = native
                            preferenceState("$stage-native-read")
                        }
                        if (predicate(native)) break
                    }
                    delay(20)
                }
            }
            preferenceState("$stage-await-success")
        } catch (failure: TimeoutCancellationException) {
            preferenceState("$stage-await-timeout")
            failure.addSuppressed(IllegalStateException("component=explore-proof seam=persistence-render stage=$stage class=timeout"))
            try { preferenceTargets("$stage-timeout"); screenshot("explore-preference-$stage-timeout") }
            catch (diagnostic: Exception) { failure.addSuppressed(diagnostic) }
            throw failure
        }
    }

    private fun preferenceState(stage: String) {
        val model = preferenceModel
        val state = model?.state?.value
        val job = model?.let {
            ExploreViewModel::class.java.getDeclaredField("preferencesJob").apply { isAccessible = true }.get(it) as Job?
        }
        preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName).put("stage", stage)
            .put("uptime_ms", SystemClock.uptimeMillis()).put("model_present", model != null)
            .put("scope_active", model?.viewModelScope?.coroutineContext?.isActive)
            .put("preferences_job_active", job?.isActive).put("saving", state?.isSaving)
            .put("read_failed", state?.preferencesReadFailed).put("write_failed", state?.preferencesWriteFailed)
            .put("history_full", state?.visitLimitReached).put("query_is_coffee", state?.query == "coffee")
            .put("loaded", state?.preferences != null).put("category_is_nature", state?.category == ExploreCategory.NATURE)
            .put("vm_nature", state?.preferences?.interests?.contains(ExploreCategory.NATURE))
            .put("vm_prefer_new", state?.preferences?.preferNew)
            .put("vm_visit_count", state?.preferences?.visits?.size)
            .put("native_nature", lastObservedPreferences?.interests?.contains(ExploreCategory.NATURE))
            .put("native_prefer_new", lastObservedPreferences?.preferNew)
            .put("native_visit_count", lastObservedPreferences?.visits?.size).toString())
    }

    private fun capacityActionTargets(stage: String) {
        preferenceState(stage)
        val nodes = compose.onAllNodesWithText(context.getString(R.string.explore_mark_visited)).fetchSemanticsNodes()
        val fullCount = compose.onAllNodesWithText(context.getString(R.string.explore_history_full)).fetchSemanticsNodes().size
        val closeCount = compose.onAllNodesWithText(context.getString(R.string.explore_close_interests)).fetchSemanticsNodes().size
        for ((index, node) in nodes.withIndex()) {
            var root = node
            while (root.parent != null) root = requireNotNull(root.parent)
            val clipped = node.boundsInRoot
            val windowBounds = node.boundsInWindow
            val position = node.positionInRoot
            val size = node.size
            val rootView = (requireNotNull(node.root) as ViewRootForTest).view
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                val insets = requireNotNull(androidx.core.view.ViewCompat.getRootWindowInsets(decor))
                val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName).put("stage", stage)
                    .put("index", index).put("matches", nodes.size).put("id", node.id).put("root_id", root.id)
                    .put("guidance_nodes", fullCount).put("interests_close_nodes", closeCount)
                    .put("disabled", node.config.contains(SemanticsProperties.Disabled)).put("placed", node.layoutInfo.isPlaced)
                    .put("clipped_left", clipped.left.toDouble()).put("clipped_top", clipped.top.toDouble())
                    .put("clipped_right", clipped.right.toDouble()).put("clipped_bottom", clipped.bottom.toDouble())
                    .put("unclipped_left", position.x.toDouble()).put("unclipped_top", position.y.toDouble())
                    .put("unclipped_width", size.width).put("unclipped_height", size.height)
                    .put("window_bottom", windowBounds.bottom.toDouble()).put("keyboard_height", keyboard)
                    .put("available_bottom", decor.height - keyboard).put("activity_focus", decor.hasWindowFocus())
                    .put("root_attached", rootView.isAttachedToWindow).put("root_focus", rootView.hasWindowFocus())
                    .put("same_window_token", rootView.windowToken === decor.windowToken)
                    .put("uptime_ms", SystemClock.uptimeMillis()).toString())
            }
        }
    }

    private fun preferenceTargets(stage: String) {
        preferenceState(stage)
        val targets = listOf(
            "nature" to compose.onAllNodesWithText(context.getString(R.string.explore_nature)).fetchSemanticsNodes(),
            "new-first" to compose.onAllNodesWithContentDescription(context.getString(R.string.explore_new_first)).fetchSemanticsNodes(),
        )
        for ((kind, nodes) in targets) for (node in nodes) {
            var root = node
            while (root.parent != null) root = requireNotNull(root.parent)
            val bounds = node.boundsInWindow
            preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName).put("stage", stage)
                .put("target", kind).put("id", node.id).put("root_id", root.id)
                .put("left", bounds.left.toDouble()).put("top", bounds.top.toDouble())
                .put("right", bounds.right.toDouble()).put("bottom", bounds.bottom.toDouble())
                .put("disabled", node.config.contains(SemanticsProperties.Disabled))
                .put("selected", node.config.getOrNull(SemanticsProperties.Selected))
                .put("toggle", node.config.getOrNull(SemanticsProperties.ToggleableState)?.toString())
                .put("activity_focus", compose.activity.window.decorView.hasWindowFocus())
                .put("activity_attached", compose.activity.window.decorView.isAttachedToWindow).toString())
        }
    }

    private fun preferenceLifecycle(stage: String, raw: Preferences) {
        nativeCasePhases.phase(stage) // Durable now, not dependent on @After's buffered flush.
        preferenceDiagnosticRows.add(JSONObject().put("case", testName.methodName).put("stage", stage)
            .put("uptime_ms", SystemClock.uptimeMillis()).put("equals_original", raw == originalPreferences)
            .put("previous_restore_known", previousRestoredPreferences != null)
            .put("equals_previous_restore", raw == previousRestoredPreferences).toString())
    }

    private companion object {
        @Volatile var previousRestoredPreferences: Preferences? = null
    }

    private fun awaitRenderedMap(): MapLibreMap {
        lateinit var view: MapView
        var map: MapLibreMap? = null
        compose.runOnUiThread {
            view = requireNotNull(findMap(compose.activity.window.decorView))
            view.getMapAsync {
                map = it
                it.triggerRepaint()
            }
        }
        // The full-frame flag did not join on this fixture. Require actual rendered label-layer
        // features instead; photographs are inspected separately, never inferred from this gate.
        compose.waitUntil(15_000) {
            var labels = false
            compose.runOnUiThread {
                labels =
                    map?.queryRenderedFeatures(
                            RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                            "place-suburb",
                            "road-label",
                        )
                        ?.isNotEmpty() == true
            }
            labels
        }
        return requireNotNull(map)
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findMap(view.getChildAt(i))?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun proveCaseFailureWitness() {
        val negative = NativeCasePhaseRule()
        val expected = IllegalStateException("native-case-statement-kill-control")
        var caught: Throwable? = null
        try {
            negative.apply(object : Statement() {
                override fun evaluate() { throw expected } // Kill the real wrapped Statement seam.
            }, Description.createTestDescription(javaClass, "observerFailureControl")).evaluate()
        } catch (failure: Throwable) {
            caught = failure
        }
        assertThat(caught).isSameInstanceAs(expected)
        val journal = requireNotNull(negative.lastLog).readLines().map(::JSONObject)
        val attempt = journal.last().getString("attempt")
        val rows = journal.filter { it.getString("attempt") == attempt }
        val failed = rows.single { it.getString("phase") == "rule-failure" }
        assertThat(failed.getString("component")).isEqualTo("native-case")
        assertThat(failed.getString("seam")).isEqualTo("wrapped-statement")
        assertThat(failed.getString("class")).isEqualTo("partial-function")
        assertThat(failed.getString("failure_type")).isEqualTo(expected.javaClass.name)
        assertThat(failed.getJSONArray("main_java_stack").length()).isGreaterThan(0)
        assertThat(rows.last().getString("phase")).isEqualTo("observer-closed")
        assertThat(rows.last().getBoolean("observer_alive")).isFalse()
        var laterOwnerRestored = false
        val cleanupFailure = runCatching {
            restoreObservers({ throw expected }, { laterOwnerRestored = true })
        }.exceptionOrNull()
        assertThat(cleanupFailure).isSameInstanceAs(expected)
        assertThat(laterOwnerRestored).isTrue()
    }

    private fun restoreObservers(vararg owners: () -> Unit) {
        val failures = owners.mapNotNull { runCatching(it).exceptionOrNull() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
            throw first
        }
    }

    private fun screenshot(name: String, nativeTail: Boolean = false) = displayCapture.screenshot(name, nativeTail)
    private fun proveCaptureJournalFailureWitness() = displayCapture.proveCaptureJournalFailureWitness()

    private fun openExplore(waitForSuggestions: Boolean = true) {
        compose.onAllNodesWithText(context.getString(R.string.nav_explore)).onLast().performClick()
        if (!waitForSuggestions) return
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithText(context.getString(R.string.explore_suggestions))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForPlaces() {
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithText(context.getString(R.string.explore_mark_visited))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}

/** Owns display resources and acquisition journals, never actions or settlement verdicts. */
private class NativeDisplayCapture(
    private val cacheDir: () -> File,
    private val caseName: () -> String?,
    private val waitForComposeIdle: () -> Unit,
) {
    fun screenshot(name: String, nativeTail: Boolean = false) {
        val entry = SystemClock.elapsedRealtimeNanos()
        waitForComposeIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Original first display acquisition and filename remain unchanged.
        val first = captureNativeDisplay(File(cacheDir(), "$name.png"))
        if (nativeTail) captureNativeTail(name, entry, first)
    }

    private fun captureNativeDisplay(file: File, dispatchCutoff: Long = Long.MAX_VALUE): JSONObject {
        val started = SystemClock.elapsedRealtimeNanos()
        if (started >= dispatchCutoff) return JSONObject().put("dispatch_refused", true).put("capture_start_ns", started)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) {
            "component=native-capture seam=display class=not-joined"
        }
        val returned = SystemClock.elapsedRealtimeNanos()
        val info = JSONObject().put("capture_start_ns", started).put("capture_return_ns", returned)
            .put("width", bitmap.width).put("height", bitmap.height).put("png", file.name)
        try {
            FileOutputStream(file).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
        val bytes = file.readBytes()
        return info.put("bytes", bytes.size).put("sha256", MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    /** Acquisition intervals only: neither elapsed time nor equal hashes certify settlement. */
    private fun captureNativeTail(name: String, entry: Long, first: JSONObject) {
        val returned = first.getLong("capture_return_ns")
        val cutoff = returned + 2_000_000_000L
        val attempt = "$name-tail-${android.os.Process.myPid()}-$returned"
        val directory = File(cacheDir(), attempt)
        check(directory.mkdir()) { "component=native-capture seam=attempt-directory class=wrong-path" }
        val frames = org.json.JSONArray()
        val missing = org.json.JSONArray()
        val journal = JSONObject().put("schema", 1).put("case", caseName()).put("stage", name)
            .put("attempt", attempt).put("pid", android.os.Process.myPid()).put("helper_entry_ns", entry)
            .put("clock", "SystemClock.elapsedRealtimeNanos").put("units", "ns")
            .put("dispatch_cutoff_ns", cutoff).put("frames", frames).put("missing_targets", missing)
            .put("termination", "partial")
            .put("scope", "Display acquisition calls; presentation timestamps and sampling gaps are unresolved")
        writeCaptureJournal(directory, journal)
        var acquisitionFailure: Throwable? = null
        try {
            val original = File(cacheDir(), "$name.png")
            val retained = File(directory, "000.png")
            original.inputStream().use { input -> FileOutputStream(retained).use { output ->
                input.copyTo(output); output.fd.sync()
            } }
            check(retained.readBytes().contentEquals(original.readBytes())) {
                "component=native-capture seam=first-frame-copy class=contract-drift"
            }
            frames.put(JSONObject(first.toString()).put("ordinal", 0).put("png", retained.name).put("target_ms", 0))
            writeCaptureJournal(directory, journal)
            val targets = listOf(100L, 200L, 400L, 800L, 1_600L)
            for ((index, milliseconds) in targets.withIndex()) {
                val target = returned + milliseconds * 1_000_000L
                val remaining = target - SystemClock.elapsedRealtimeNanos()
                if (remaining > 0) SystemClock.sleep((remaining + 999_999L) / 1_000_000L)
                val dispatchCheck = SystemClock.elapsedRealtimeNanos()
                if (dispatchCheck >= cutoff) {
                    missing.put(JSONObject().put("target_ms", milliseconds).put("dispatch_refused_ns", dispatchCheck))
                    writeCaptureJournal(directory, journal)
                    continue
                }
                // No extra Compose/Instrumentation idle wait, semantic polling,
                // event dispatch, retap or clock advancement between these captures.
                val frame = captureNativeDisplay(File(directory, "00${index + 1}.png"), cutoff)
                if (frame.optBoolean("dispatch_refused")) {
                    missing.put(JSONObject().put("target_ms", milliseconds)
                        .put("dispatch_refused_ns", frame.getLong("capture_start_ns")))
                    writeCaptureJournal(directory, journal)
                    continue
                }
                frame.put("ordinal", index + 1).put("target_ms", milliseconds)
                    .put("lateness_ns", frame.getLong("capture_start_ns") - target)
                    .put("capture_overran_cutoff", frame.getLong("capture_return_ns") > cutoff)
                frames.put(frame)
                writeCaptureJournal(directory, journal)
            }
            val complete = frames.length() == 6 && missing.length() == 0 &&
                frames.getJSONObject(5).getLong("capture_return_ns") <= cutoff
            journal.put("termination", if (complete) "complete-acquisition" else "partial")
        } catch (failure: Throwable) {
            acquisitionFailure = failure
            throw failure
        } finally {
            journal.put("journal_close_ns", SystemClock.elapsedRealtimeNanos())
            try {
                writeCaptureJournal(directory, journal) // Retain partial pixels/metadata on failure too.
            } catch (diagnostic: Throwable) {
                val original = acquisitionFailure
                if (original == null) throw diagnostic
                if (diagnostic !== original) original.addSuppressed(diagnostic)
            }
        }
    }

    private fun writeCaptureJournal(directory: File, journal: JSONObject) {
        try {
            FileOutputStream(File(directory, "capture.json")).use {
                it.write((journal.toString() + "\n").toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
        } catch (failure: java.io.IOException) {
            throw IllegalStateException("component=native-capture seam=journal class=wrong-path", failure)
        }
    }

    fun proveCaptureJournalFailureWitness() {
        val directory = File(cacheDir(), "capture-journal-kill-${SystemClock.elapsedRealtimeNanos()}")
        check(directory.mkdir())
        try {
            check(File(directory, "capture.json").mkdir()) // Kill the actual new file-open sink.
            val failure = runCatching { writeCaptureJournal(directory, JSONObject()) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo("component=native-capture seam=journal class=wrong-path")
        } finally {
            check(directory.deleteRecursively()) // Only this newly created empty kill fixture.
        }
    }
}

/** TEST-only outer lifecycle witness. Never invokes UI, native input, setters or restoration. */
private class NativeCasePhaseRule : TestRule {
    @Volatile var lastLog: File? = null
        private set
    @Volatile private var emitter: ((String, JSONObject) -> Unit)? = null

    fun phase(value: String) = requireNotNull(emitter) {
        "component=native-case seam=phase-writer class=not-joined"
    }.invoke(value, JSONObject())

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            // This runs BEFORE ActivityScenario and before any private cache write.
            check(Build.MODEL.startsWith("sdk_gphone")) {
                "component=native-case seam=target class=wrong-path"
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "au.com.ausroads.debug")
            val method = requireNotNull(description.methodName)
            check(method.matches(Regex("[A-Za-z0-9_]+")))
            val pid = android.os.Process.myPid()
            val attempt = "$pid-${SystemClock.uptimeMillis()}"
            val log = File(context.cacheDir, "explore-case-phases-$attempt-$method.jsonl")
            check(log.createNewFile()) { "component=native-case seam=journal-create class=wrong-path" }
            lastLog = log
            val lastPhase = AtomicReference("rule-enter")
            val diagnosticFailure = AtomicReference<Throwable?>()
            // The monitor has its own sink: stalled monitor I/O cannot own the runner journal lock.
            val stackLog = File(context.cacheDir, "explore-case-stacks-$attempt-$method.jsonl")
            val done = CountDownLatch(1)
            val owner = Thread.currentThread()
            var seam = "before-fixture-copy"
            fun write(phase: String, extra: JSONObject = JSONObject(), destination: File = log) {
                val row = JSONObject().put("component", "native-case").put("case", method)
                    .put("attempt", attempt).put("pid", pid).put("phase", phase)
                    .put("uptime_ms", SystemClock.uptimeMillis())
                extra.keys().forEach { key -> row.put(key, extra.get(key)) }
                FileOutputStream(destination, true).use { stream ->
                    stream.write((row.toString() + "\n").toByteArray(Charsets.UTF_8))
                    stream.fd.sync() // Survives process death, not just normal @After.
                }
                if (destination === log) lastPhase.set(phase)
            }
            fun stacks(): JSONObject {
                val main = android.os.Looper.getMainLooper().thread
                return JSONObject().put("main_thread_state", main.state.name)
                    .put("main_java_stack", org.json.JSONArray(main.stackTrace.map { it.toString() }))
                    .put("runner_thread_state", owner.state.name)
                    .put("runner_java_stack", org.json.JSONArray(owner.stackTrace.map { it.toString() }))
            }
            emitter = { phase, extra ->
                try { write(phase, extra) }
                catch (diagnostic: Throwable) {
                    // Do not prevent @After restoration when a later diagnostic write fails.
                    diagnosticFailure.compareAndSet(null, diagnostic)
                }
            }
            val monitor = Thread({
                try {
                    // Samples are not an ANR diagnosis or acceptance deadline. No UI-thread work.
                    repeat(3) {
                        if (done.await(20, TimeUnit.SECONDS)) return@Thread
                        write("inflight-java-stack", stacks().put("last_phase", lastPhase.get()), stackLog)
                    }
                } catch (failure: Throwable) {
                    diagnosticFailure.set(failure)
                }
            }, "native-case-observer").apply { isDaemon = true }
            var failure: Throwable? = null
            try {
                val original = File(context.filesDir, "datastore/exploration_v1.preferences_pb")
                val info = JSONObject().put("preferences_present", original.exists())
                if (original.exists()) {
                    check(original.isFile)
                    val bytes = original.readBytes()
                    check(original.readBytes().contentEquals(bytes)) {
                        "component=native-case seam=before-fixture-copy class=partial-function"
                    }
                    val backup = File(context.cacheDir, "explore-original-$attempt-$method.preferences_pb")
                    check(!backup.exists())
                    FileOutputStream(backup).use { it.write(bytes); it.fd.sync() }
                    check(backup.readBytes().contentEquals(bytes))
                    info.put("preferences_sha256", MessageDigest.getInstance("SHA-256")
                        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })
                        .put("preferences_bytes", bytes.size).put("backup_name", backup.name)
                }
                seam = "phase-writer"
                write("rule-enter", info) // Boot-path cache write/backup self-check before base.
                seam = "observer-start"
                monitor.start()
                seam = "wrapped-statement"
                base.evaluate() // Exact original ActivityScenario/@Before/body/@After/teardown.
                seam = "phase-writer"
                write("wrapped-statement-return")
            } catch (caught: Throwable) {
                failure = caught
                try {
                    write("rule-failure", stacks().put("seam", seam)
                        .put("class", "partial-function").put("failure_type", caught.javaClass.name))
                } catch (diagnostic: Throwable) { caught.addSuppressed(diagnostic) }
            } finally {
                done.countDown()
                emitter = null
                try {
                    monitor.join(1_000)
                    check(!monitor.isAlive) { "component=native-case seam=observer-close class=partial-function" }
                    diagnosticFailure.get()?.let { throw it }
                    write("observer-closed", JSONObject().put("observer_alive", monitor.isAlive))
                } catch (diagnostic: Throwable) {
                    if (failure == null) failure = diagnostic else if (failure !== diagnostic) failure!!.addSuppressed(diagnostic)
                }
            }
            failure?.let { throw it } // Diagnostic work cannot turn the original failure into PASS.
        }
    }
}
