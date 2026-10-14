package au.com.ausroads.ui.map

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.ausroads.AusRoadsApp
import au.com.ausroads.BuildConfig
import au.com.ausroads.MainActivity
import au.com.ausroads.R
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.data.pins.PinDatabase
import au.com.ausroads.data.routes.RouteHistoryDatabase
import au.com.ausroads.data.settings.settingsDataStore
import au.com.ausroads.feature.navigation.DiagnosedNavigationLocationSource
import au.com.ausroads.feature.navigation.NavigationGpsEvent
import au.com.ausroads.feature.navigation.NavigationLocation
import au.com.ausroads.feature.navigation.NavigationLocationSource
import au.com.ausroads.feature.navigation.NavigationState
import au.com.ausroads.feature.navigation.NavigationViewModel
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import au.com.ausroads.ui.explore.DataStoreExplorationStore
import au.com.ausroads.ui.settings.SettingsViewModel
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Real MainActivity content, its existing map-entry VMs, native MapView and production FGS.
 * No setContent, Hilt test graph, permission grant, settings toggle or production test hook.
 *
 * Runner routing (select methods, NOT this whole class on each flavor):
 * offline: offlineStartNavigationRefusesLocationWithoutStartingSession
 * withNetwork: withNetworkColdNoFixShowsGpsUnavailableAndEndCleansCallbacks
 * withNetwork: withNetworkSourceFailurePersistsAttemptsAndNotificationStopCrossesPackReload
 * A wrong flavor/device/precondition FAILS before fixtures; it is never an ignored PASS.
 * Network requires parent-pregranted FINE and (API 33+) POST_NOTIFICATIONS on the exact
 * disposable sdk_gphone/ranchu-or-goldfish guest, with app/channel notifications enabled.
 *
 * Controlled boundaries: RouteViewModel.routingEngine returns a request-derived route;
 * NavigationViewModel.locationProvider supplies no-fix/failed callback flows. The ORIGINAL
 * network provider's recordGpsEvent forwards VM events through its production PlacesHealth
 * callback. These prove VM -> real Activity/health/FGS crossings, NOT Play Services Task
 * registration/removal or Valhalla routing. Actual Task kill controls live separately in
 * FusedNavigationLocationSourceTest. The map blue-dot provider remains unmodified.
 *
 * Source joins: MainActivity.onCreate -> AusRoadsApp -> AusRoadsNavHost's map composable
 * owns both VMs; MapScreen mounts RouteSheet, NavigationOverlay, the service and Stop bus
 * even while replacing assets. RouteSheet Start and NavigationOverlay End/Dismiss are
 * clicked, and notification Stop sends the very PendingIntent read from the live FGS.
 */
@RunWith(AndroidJUnit4::class)
class NavigationFailureJourneyInstrumentedTest {
    // Empty rule deliberately does NOT launch an Activity before the physical-device guard.
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var activity: MainActivity
    private lateinit var models: MapModels
    private lateinit var nativeView: MapView
    private lateinit var map: MapLibreMap
    private val restorations = mutableListOf<() -> Unit>()
    private val preferences = mutableListOf<Pair<DataStore<Preferences>, Preferences>>()
    private val tables = mutableListOf<TableSnapshot>()
    private val ownedDirectories = mutableListOf<File>()
    private var source: ControlledGpsSource? = null
    private var routeFixture: ControlledRouteEngine? = null
    private var startedHistory: TableSnapshot? = null
    private val nativeFrames = AtomicInteger()
    private val nativeStyles = AtomicInteger()
    private val nativeLongClicks = AtomicInteger()
    private val frameWitness = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
        nativeFrames.incrementAndGet(); Unit
    }
    private val styleWitness = MapView.OnDidFinishLoadingStyleListener { nativeStyles.incrementAndGet(); Unit }
    private val longClickWitness = MapLibreMap.OnMapLongClickListener { nativeLongClicks.incrementAndGet(); false }

    @Before fun emulatorOnlyBeforeAnyFixtureOrActivity() {
        check(Build.MODEL.startsWith("sdk_gphone") && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "component=navigation-proof seam=guest-guard class=physical-device-refused"
        }
    }

    @After fun restoreWholeOwnedStateAndCallbacks() {
        val failed = mutableListOf<String>()
        fun restore(name: String, action: () -> Unit) {
            if (runCatching(action).isFailure) failed += name
        }
        // Teardown calls cannot satisfy any journey assertion below.
        if (::models.isInitialized) restore("session") {
            onMain { models.navigation.stopNavigation(); models.routing.clearRoute() }
            compose.waitUntil(5_000) { source?.let { it.removals.get() == it.callbacks.size } ?: true }
        }
        restorations.asReversed().forEach { action -> restore("dependency-or-pack", action) }
        restorations.clear()
        restore("activity") { scenario?.close(); scenario = null }
        // Independent cleanup still runs if native fallback recovery failed partway through.
        ownedDirectories.asReversed().forEach { directory -> restore("owned-directory") {
            check(directory.deleteRecursively())
        } }
        tables.asReversed().forEach { table -> restore("whole-table") { table.restore(context) } }
        preferences.asReversed().forEach { (store, original) ->
            restore("whole-preferences") {
                runBlocking {
                    withTimeout(10_000) {
                        if (store.data.first() != original) store.updateData { original }
                        check(store.data.first() == original) // Never print private keys/values.
                    }
                }
            }
        }
        source?.let { controlled -> restore("callbacks") {
            check(controlled.callbacks.all { it.isClosedForSend })
        } }
        check(failed.isEmpty()) { "component=navigation-proof seam=restoration class=not-joined owners=$failed" }
    }

    @Test fun offlineStartNavigationRefusesLocationWithoutStartingSession() {
        requireFlavor("offline")
        val declared = requestedPermissions()
        assertThat(declared.intersect(setOf(Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACCESS_MEDIA_LOCATION))).isEmpty()
        assertThat(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services.orEmpty().any { it.name == SERVICE_CLASS }).isFalse()
        launchAndSnapshot()
        val controlled = replaceGpsSource(failCollections = false)
        startUsingActualDirectionsAndRouteSheet()
        compose.waitUntil(10_000) { models.navigation.state.value is NavigationState.LocationUnavailable }
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_location_unavailable))
            .assertIsDisplayed()
        assertNoNavigationBanners()
        assertThat(controlled.attempts).isEmpty()
        assertThat(controlled.events).isEmpty()
        assertServiceStopped()
        assertPersonalStateUnchanged(includeRoutes = true)
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_dismiss))
            .assertIsEnabled().performClick()
        compose.waitUntil(5_000) { models.navigation.state.value is NavigationState.Idle }
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_location_unavailable))
            .assertDoesNotExist()
        assertPersonalStateUnchanged(includeRoutes = true)
        assertThat(models.routing.routeState.value).isInstanceOf(RouteUiState.Active::class.java)
        check((models.routing.routeState.value as RouteUiState.Active).result === routeFixture?.result)
        compose.onNodeWithText(context.getString(R.string.route_start_navigation)).assertIsDisplayed()
        stage("offline-refusal-and-dismiss-retains-route-positive")
    }

    @Test fun withNetworkColdNoFixShowsGpsUnavailableAndEndCleansCallbacks() {
        requireNetworkGuestPermissions()
        launchAndSnapshot()
        val before = gpsRow()?.optLong("failures") ?: 0L
        val controlled = replaceGpsSource(failCollections = false)
        val startedAt = System.currentTimeMillis()
        startUsingActualDirectionsAndRouteSheet()
        awaitAcquiringAndForegroundService(controlled)
        assertStartedRouteAndPriorHistory()
        // Real Android monotonic time/watchdog: no clock substitution or fabricated VM state.
        compose.waitUntil(NavigationViewModel.GPS_STALE_MS + NavigationViewModel.GPS_WATCHDOG_INTERVAL_MS + 5_000) {
            (models.navigation.state.value as? NavigationState.Navigating)?.gpsLost == true
        }
        assertColdLostBanner()
        val row = awaitGpsRow(NavigationGpsEvent.NO_FIX, 1, before, startedAt)
        assertThat(row.getLong("failures")).isEqualTo(before + 1)
        assertThat(controlled.attempts).containsExactly(1)
        assertThat(controlled.events.count { it.first == NavigationGpsEvent.NO_FIX }).isEqualTo(1)
        stage("cold-no-fix-visible-and-persistent-positive")
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_end))
            .assertIsDisplayed().assertIsEnabled().performClick()
        awaitStoppedAndRejectLateCallbacks(controlled)
        assertPersonalStateUnchanged(includeRoutes = false)
        stage("actual-end-idle-service-and-late-callback-negative")
    }

    @Test fun withNetworkSourceFailurePersistsAttemptsAndNotificationStopCrossesPackReload() {
        requireNetworkGuestPermissions()
        launchAndSnapshot()
        val before = gpsRow()?.optLong("failures") ?: 0L
        val controlled = replaceGpsSource(failCollections = true)
        val startedAt = System.currentTimeMillis()
        startUsingActualDirectionsAndRouteSheet()
        awaitAcquiringAndForegroundService(controlled)
        assertStartedRouteAndPriorHistory()
        compose.waitUntil(20_000) {
            controlled.events.contains(NavigationGpsEvent.RETRIES_EXHAUSTED to (NavigationViewModel.MAX_GPS_RETRIES + 1))
        }
        val lastAttempt = NavigationViewModel.MAX_GPS_RETRIES + 1
        assertThat(controlled.attempts).containsExactlyElementsIn((1..lastAttempt).toList()).inOrder()
        assertThat(controlled.events.filter { it.first == NavigationGpsEvent.COLLECTION_FAILED }.map { it.second })
            .containsExactlyElementsIn((1..lastAttempt).toList()).inOrder()
        compose.waitUntil(5_000) { controlled.removals.get() == lastAttempt }
        assertColdLostBanner()
        val row = awaitGpsRow(NavigationGpsEvent.RETRIES_EXHAUSTED, lastAttempt, before, startedAt)
        assertThat(row.getLong("failures")).isEqualTo(before + controlled.events.count { it.first.failed })
        stage("controlled-collection-failure-bounded-attempt-sink-positive")

        val notification = requireNotNull(navigationNotification())
        assertThat(notification.notification.channelId).isEqualTo("navigation")
        val stop = notification.notification.actions.single {
            it.title.toString() == networkText("nav_service_stop")
        }.actionIntent
        val sent = AtomicBoolean(false)
        val dispatchFailed = AtomicBoolean(false)
        val nativeReloadStarts = AtomicInteger()
        val listener = MapView.OnWillStartLoadingMapListener {
            nativeReloadStarts.incrementAndGet()
            if (sent.compareAndSet(false, true)) {
                // Real notification action -> real Service.ACTION_STOP -> MapScreen's bus collector.
                // Dispatch is tied to the native reload-start witness, not a nominal later reload.
                if (runCatching { stop.send() }.isFailure) dispatchFailed.set(true)
            }
        }
        restorations += { onMain { nativeView.removeOnWillStartLoadingMapListener(listener) } }
        onMain { nativeView.addOnWillStartLoadingMapListener(listener) }
        val restorePack = publishControlledPackReload()
        compose.waitUntil(15_000) { sent.get() }
        assertThat(nativeReloadStarts.get()).isAtLeast(1)
        assertThat(dispatchFailed.get()).isFalse()
        awaitStoppedAndRejectLateCallbacks(controlled)
        awaitNativeGeometry()
        onMain {
            check(findMap(activity.window.decorView) === nativeView)
            val current = findExistingMapModels()
            check(current.store === models.store && current.navigation === models.navigation && current.routing === models.routing)
            nativeView.removeOnWillStartLoadingMapListener(listener)
        }
        restorePack() // Positive control: same real screen reloads the bundled style again.
        awaitNativeGeometry()
        longPressMapAndAwaitSheet() // Physical control of the real listener after both reloads.
        compose.onNodeWithText(context.getString(R.string.map_drop_pin_title)).assertIsDisplayed()
        Espresso.pressBack()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.map_drop_pin_title)).fetchSemanticsNodes().isEmpty()
        }
        assertServiceStopped()
        assertPersonalStateUnchanged(includeRoutes = false)
        stage("notification-stop-at-native-reload-and-bundled-control-positive")
    }

    private fun requireFlavor(expected: String) {
        check(BuildConfig.FLAVOR == expected) {
            "component=navigation-proof seam=flavor-routing class=unsupported-flavor expected=$expected"
        }
        check(!serviceRunning() && navigationNotification() == null) {
            "component=navigation-proof seam=session-isolation class=existing-session-refused"
        }
        // Refuse an adopted pack (including malformed authority), never overwrite it with fixtures.
        check(!File(context.filesDir, "mappacks/au-sa/current.json").exists())
        check((context.applicationContext as AusRoadsApp).mapPackManager.get().installed.value == null)
    }

    private fun requireNetworkGuestPermissions() {
        requireFlavor("withNetwork")
        check(Manifest.permission.ACCESS_FINE_LOCATION in requestedPermissions())
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "component=navigation-proof seam=parent-input class=fine-permission-required"
        }
        if (Build.VERSION.SDK_INT >= 33) check(ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            "component=navigation-proof seam=parent-input class=notification-permission-required"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled() && manager.getNotificationChannel("navigation")?.importance != NotificationManager.IMPORTANCE_NONE) {
            "component=navigation-proof seam=parent-input class=notifications-disabled"
        }
    }

    private fun requestedPermissions(): Set<String> = context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()

    private fun launchAndSnapshot() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity = it }
        val settings = onMain { ViewModelProvider(activity)[SettingsViewModel::class.java] }
        compose.waitUntil(10_000) { settings.storeState.value.canEdit }
        snapshotPreferences(context.settingsDataStore)
        val exploration = DataStoreExplorationStore(context)
        @Suppress("UNCHECKED_CAST")
        val rawExploration = DataStoreExplorationStore::class.java.getDeclaredField("dataStore")
            .apply { isAccessible = true }.get(exploration) as DataStore<Preferences>
        snapshotPreferences(rawExploration) // Whole raw values, including unknown keys/visit records.
        compose.waitUntil(10_000) { onMain { findMap(activity.window.decorView) != null } }
        nativeView = onMain { requireNotNull(findMap(activity.window.decorView)) }
        onMain {
            nativeView.addOnDidFinishRenderingFrameListener(frameWitness)
            nativeView.addOnDidFinishLoadingStyleListener(styleWitness)
        }
        restorations += { onMain {
            nativeView.removeOnDidFinishRenderingFrameListener(frameWitness)
            nativeView.removeOnDidFinishLoadingStyleListener(styleWitness)
            if (::map.isInitialized) map.removeOnMapLongClickListener(longClickWitness)
        } }
        var observed: MapLibreMap? = null
        onMain { nativeView.getMapAsync { observed = it; it.triggerRepaint() } }
        compose.waitUntil(10_000) { observed != null }
        map = requireNotNull(observed)
        awaitNativeGeometry()
        models = onMain { findExistingMapModels() }
        check(models.navigation.state.value is NavigationState.Idle && models.routing.routeState.value is RouteUiState.Idle)
        for ((name, table) in listOf(PinDatabase.DATABASE_NAME to "pins", RouteHistoryDatabase.DATABASE_NAME to "route_history")) {
            compose.waitUntil(10_000) { runCatching { TableSnapshot.capture(context, name, table) }.isSuccess }
            tables += TableSnapshot.capture(context, name, table)
        }
        val field = RouteViewModel::class.java.getDeclaredField("routingEngine").apply { isAccessible = true }
        val original = onMain { field.get(models.routing) as RoutingEngine }
        restorations += { onMain { field.set(models.routing, original); check(field.get(models.routing) === original) } }
        routeFixture = ControlledRouteEngine().also { controlled -> onMain { field.set(models.routing, controlled) } }
        stage("real-activity-map-owner-native-geometry-control")
    }

    private fun snapshotPreferences(store: DataStore<Preferences>) {
        val original = runBlocking { withTimeout(10_000) { store.data.first() } }
        preferences += store to original
    }

    private data class MapModels(val store: ViewModelStore, val navigation: NavigationViewModel, val routing: RouteViewModel)

    private fun findExistingMapModels(): MapModels {
        // Lookup existing instances ONLY. Never ViewModelProvider(activity)[NavigationViewModel]
        // (that would manufacture the wrong Activity owner). AusRoadsNavHost creates these
        // together only in composable("map"). Pinned AndroidX store reflection is fail-closed.
        fun values(store: ViewModelStore): Collection<ViewModel> {
            @Suppress("UNCHECKED_CAST")
            val values = ViewModelStore::class.java.getDeclaredField("map").apply { isAccessible = true }
                .get(store) as Map<String, ViewModel>
            return values.values.toList()
        }
        val activityModels = values(activity.viewModelStore)
        check(activityModels.none { it is NavigationViewModel || it is RouteViewModel })
        val controller = activityModels.single { it.javaClass.name == "androidx.navigation.NavControllerViewModel" }
        @Suppress("UNCHECKED_CAST")
        val entryStores = controller.javaClass.getDeclaredField("viewModelStores").apply { isAccessible = true }
            .get(controller) as Map<String, ViewModelStore>
        val store = entryStores.values.single { values(it).any { model -> model is NavigationViewModel } }
        val mapModels = values(store)
        return MapModels(store, mapModels.filterIsInstance<NavigationViewModel>().single(),
            mapModels.filterIsInstance<RouteViewModel>().single())
    }

    private fun replaceGpsSource(failCollections: Boolean): ControlledGpsSource {
        val field = NavigationViewModel::class.java.getDeclaredField("locationProvider").apply { isAccessible = true }
        val original = onMain { field.get(models.navigation) as NavigationLocationSource }
        val diagnosed = original as? DiagnosedNavigationLocationSource
        if (BuildConfig.FLAVOR == "withNetwork") check(diagnosed != null &&
            original.javaClass.name == "au.com.ausroads.di.FusedNavigationLocationSource")
        else check(diagnosed == null)
        restorations += { onMain { field.set(models.navigation, original); check(field.get(models.navigation) === original) } }
        return ControlledGpsSource(diagnosed, failCollections).also { controlled ->
            source = controlled
            onMain { field.set(models.navigation, controlled) }
        }
    }

    private fun longPressMapAndAwaitSheet() {
        // Witness the SDK's raw delivery before its consuming application listener.
        // Return false, never replace or directly invoke the production consumer.
        onMain {
            map.removeOnMapLongClickListener(longClickWitness)
            map.addOnMapLongClickListener(longClickWitness)
            val detector = MapView::class.java.getDeclaredField("mapGestureDetector")
                .apply { isAccessible = true }.get(nativeView)
            val list = detector.javaClass.getDeclaredField("onMapLongClickListenerList")
                .apply { isAccessible = true }.get(detector)
            list.javaClass.getMethod("remove", Any::class.java).invoke(list, longClickWitness)
            list.javaClass.getMethod("add", Int::class.javaPrimitiveType, Any::class.java)
                .invoke(list, 0, longClickWitness)
            check(list.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(list, 0) === longClickWitness)
        }
        // Caller's original geometry deadline owns the real consumer/UI barrier too;
        // never renew that deadline here or change the physical gesture/sheet deadline.
        val consumers = onMain { longClickConsumers() }
        check(consumers.size == 1) { "component=navigation-proof seam=input-ready class=consumer-not-joined" }
        val before = nativeLongClicks.get()
        nativeSnapshot("before-physical-longpress")
        val position = IntArray(2)
        onMain { nativeView.getLocationOnScreen(position) }
        val down = SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                position[0] + nativeView.width * 0.70f, position[1] + nativeView.height * 0.55f, 0)
            try { check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        inject(MotionEvent.ACTION_DOWN)
        Thread.sleep(700) // Actual native long-press clock, not a direct MapScreen callback.
        inject(MotionEvent.ACTION_UP)
        check(onMain { longClickConsumers() } == consumers) {
            "component=navigation-proof seam=physical-longpress class=consumer-changed-during-gesture"
        }
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText(context.getString(R.string.map_drop_pin_title)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            nativeSnapshot("physical-longpress-timeout")
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                File(context.cacheDir, "navigation-longpress-${testName.methodName}.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            throw failure
        }
        check(nativeLongClicks.get() == before + 1) { "component=navigation-proof seam=physical-longpress class=raw-sdk-not-joined" }
        nativeSnapshot("physical-longpress-positive")
    }

    private fun startUsingActualDirectionsAndRouteSheet() {
        longPressMapAndAwaitSheet()
        compose.onNodeWithText(context.getString(R.string.route_directions)).assertIsEnabled().performClick()
        compose.waitUntil(10_000) { models.routing.routeState.value is RouteUiState.Active }
        val controlled = requireNotNull(routeFixture)
        check(controlled.requests.isNotEmpty())
        check((models.routing.routeState.value as RouteUiState.Active).result === controlled.result)
        compose.onNodeWithText(context.getString(R.string.route_start_navigation))
            .assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun awaitAcquiringAndForegroundService(controlled: ControlledGpsSource) {
        compose.waitUntil(5_000) { controlled.attempts.isNotEmpty() }
        val current = models.navigation.state.value as NavigationState.Navigating
        assertThat(current.awaitingGpsFix).isTrue()
        assertThat(current.gpsLost).isFalse()
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_acquiring_gps)).assertIsDisplayed()
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_gps_lost)).assertDoesNotExist()
        compose.waitUntil(5_000) { serviceRunning() && navigationNotification() != null }
        assertThat(navigationNotification()!!.notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            .isEqualTo(networkText("nav_service_title"))
    }

    private fun assertColdLostBanner() {
        val current = models.navigation.state.value as NavigationState.Navigating
        assertThat(current.awaitingGpsFix).isTrue() // The combined state that exposed the original consumer bug.
        assertThat(current.gpsLost).isTrue()
        assertThat(current.currentSpeedKmh).isEqualTo(0.0)
        assertThat(current.remainingDistanceMeters).isEqualTo(requireNotNull(routeFixture?.result).distanceMeters.toDouble())
        assertThat(current.remainingDurationSeconds).isEqualTo(requireNotNull(routeFixture?.result).durationSeconds.toDouble())
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_gps_lost)).assertIsDisplayed()
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_acquiring_gps)).assertDoesNotExist()
        compose.onNodeWithText(PRIVATE_SOURCE_MESSAGE).assertDoesNotExist()
        compose.onNodeWithText(navText(au.com.ausroads.feature.navigation.R.string.nav_end)).assertIsDisplayed().assertIsEnabled()
    }

    private fun awaitGpsRow(event: NavigationGpsEvent, attempt: Int, before: Long, since: Long): JSONObject {
        compose.waitUntil(10_000) {
            gpsRow()?.let { it.optString("last_outcome") == event.name && it.optInt("last_attempt") == attempt &&
                it.optLong("failures") > before && it.optLong("last_failure_ms") >= since } == true
        }
        val row = requireNotNull(gpsRow())
        assertThat(row.getString("last_error")).isEqualTo("seam_failed")
        val keys = row.keys().asSequence().toSet()
        assertThat(keys - setOf("last_outcome", "last_attempt", "failures", "last_failure_ms", "last_error", "last_success_ms")).isEmpty()
        assertThat(row.toString()).doesNotContain(PRIVATE_SOURCE_MESSAGE)
        assertThat(row.toString()).doesNotContain("138.")
        assertThat(row.toString()).doesNotContain(context.filesDir.path)
        return row
    }

    private fun gpsRow(): JSONObject? = runCatching {
        JSONObject(File(context.noBackupFilesDir, "places-health.json").readText()).optJSONObject("NAVIGATION_GPS")
    }.getOrNull()

    private fun awaitStoppedAndRejectLateCallbacks(controlled: ControlledGpsSource) {
        compose.waitUntil(5_000) { models.navigation.state.value is NavigationState.Idle &&
            controlled.removals.get() == controlled.callbacks.size && !serviceRunning() && navigationNotification() == null }
        val route = requireNotNull(routeFixture?.result)
        val point = route.geometry.first()
        val late = NavigationLocation(point.latitude, point.longitude, 99.0, 0f, 5f, System.currentTimeMillis())
        check(controlled.callbacks.isNotEmpty()) // No vacuous all-closed positive.
        controlled.callbacks.forEach { assertThat(it.trySend(late).isFailure).isTrue() }
        val attempts = controlled.attempts.toList()
        val events = controlled.events.toList()
        // Cross the next retry/watchdog deadline on the real clock. No late resurrection or health event.
        val until = SystemClock.elapsedRealtime() + maxOf(NavigationViewModel.GPS_RETRY_BASE_MS,
            NavigationViewModel.GPS_WATCHDOG_INTERVAL_MS) + 500
        while (SystemClock.elapsedRealtime() < until) {
            assertThat(models.navigation.state.value).isEqualTo(NavigationState.Idle)
            assertThat(controlled.attempts).containsExactlyElementsIn(attempts).inOrder()
            assertThat(controlled.events).containsExactlyElementsIn(events).inOrder()
            assertServiceStopped()
            Thread.sleep(50)
        }
        onMain {
            for (name in listOf("navigationJob", "gpsWatchdogJob", "recalcJob", "hazardJob")) {
                check(NavigationViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(models.navigation) == null)
            }
        }
        assertNoNavigationBanners()
        assertThat(models.routing.routeState.value).isEqualTo(RouteUiState.Idle)
        check(TableSnapshot.capture(context, RouteHistoryDatabase.DATABASE_NAME, "route_history") == startedHistory) {
            "component=navigation-proof seam=started-history class=stop-mutated-or-duplicated"
        }
    }

    private fun assertStartedRouteAndPriorHistory() {
        val before = tables.single { it.table == "route_history" }
        val result = requireNotNull(routeFixture?.result)
        val first = result.geometry.first()
        val last = result.geometry.last()
        fun matches(snapshot: TableSnapshot): Boolean {
            if (snapshot.columns != before.columns || snapshot.version != before.version ||
                !snapshot.rows.containsAll(before.rows)) return false
            val added = snapshot.rows.filter { it !in before.rows }
            if (added.size != 1) return false
            val row = added.single()
            fun value(column: String) = row[snapshot.columns.indexOf(column)]
            return value("originLat") == first.latitude && value("originLon") == first.longitude &&
                value("destLat") == last.latitude && value("destLon") == last.longitude &&
                value("distanceMeters") == result.distanceMeters.toLong() &&
                value("durationSeconds") == result.durationSeconds.toLong()
        }
        compose.waitUntil(10_000) {
            matches(TableSnapshot.capture(context, RouteHistoryDatabase.DATABASE_NAME, "route_history"))
        }
        startedHistory = TableSnapshot.capture(context, RouteHistoryDatabase.DATABASE_NAME, "route_history")
        check(matches(requireNotNull(startedHistory)))
        check((models.routing.routeState.value as? RouteUiState.Active)?.result === result) {
            "component=navigation-proof seam=started-route class=route-cleared-at-start"
        }
        stage("exact-started-history-and-retained-route-positive")
    }

    private fun assertNoNavigationBanners() {
        for (resource in listOf(au.com.ausroads.feature.navigation.R.string.nav_end,
            au.com.ausroads.feature.navigation.R.string.nav_gps_lost,
            au.com.ausroads.feature.navigation.R.string.nav_acquiring_gps)) {
            compose.onNodeWithText(navText(resource)).assertDoesNotExist()
        }
    }

    private fun assertServiceStopped() {
        assertThat(serviceRunning()).isFalse()
        assertThat(navigationNotification()).isNull()
    }

    @Suppress("DEPRECATION") // API26+ permits inspection of this application's own services only.
    private fun serviceRunning(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == SERVICE_CLASS }

    private fun navigationNotification(): StatusBarNotification? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.singleOrNull { it.packageName == context.packageName && it.id == 4001 }

    private fun publishControlledPackReload(): () -> Unit {
        val manager = (context.applicationContext as AusRoadsApp).mapPackManager.get()
        @Suppress("UNCHECKED_CAST")
        val installed = manager.javaClass.getDeclaredField("_installed").apply { isAccessible = true }
            .get(manager) as MutableStateFlow<InstalledPack?>
        val original = installed.value
        val current = File(context.filesDir, "mappacks/au-sa/current.json")
        val originalMetadata = current.takeIf { it.isFile }?.readBytes()
        check(original == null && originalMetadata == null)
        val store = PackStateStore(context)
        val version = "navigation-proof-${System.nanoTime()}"
        val directory = store.packDir(version)
        check(!directory.exists())
        ownedDirectories += directory // Register partial-copy cleanup before mkdir/write.
        var restored = false
        val restorePack: () -> Unit = {
            if (!restored) {
                if (originalMetadata == null) check(!current.exists() || current.delete()) else current.writeBytes(originalMetadata)
                installed.value = original
                compose.waitUntil(15_000) { onMain { styleUses(File(context.filesDir, "adelaide-test-tiles.mbtiles")) } }
                restored = true
            }
        }
        restorations += restorePack // Registered BEFORE any copy, publication or listener-triggered Stop.
        check(directory.mkdirs())
        val tiles = File(directory, "tiles.mbtiles")
        context.assets.open("maptile/adelaide-test-tiles.mbtiles").use { input -> tiles.outputStream().use { input.copyTo(it) } }
        val pack = InstalledPack(version, "AU-SA", Instant.fromEpochMilliseconds(0), tiles.length(),
            "tiles.mbtiles", null, null, "a".repeat(64))
        runBlocking { store.writeCurrent(pack) }
        installed.value = pack // Actual publisher -> real MapScreen preparation/style lifecycle.
        compose.waitUntil(15_000) { onMain { styleUses(tiles) } }
        return restorePack
    }

    private fun styleUses(tiles: File): Boolean = map.style?.let { style ->
        style.getLayer("place-suburb") != null && JSONObject(style.json).getJSONObject("sources")
            .getJSONObject("aus-roads-tiles").getString("url") == "mbtiles://${tiles.canonicalPath}"
    } == true

    private fun awaitNativeGeometry() {
        try {
            compose.waitUntil(15_000) {
                onMain { nativeView.isAttachedToWindow && nativeView.isShown && nativeView.hasWindowFocus() &&
                    nativeView.width > 0 && nativeView.height > 0 && map.style != null &&
                    longClickConsumers().isNotEmpty() &&
                    map.queryRenderedFeatures(RectF(0f, 0f, nativeView.width.toFloat(), nativeView.height.toFloat()),
                        "road-minor", "road-secondary", "road-primary", "road-motorway", "road-label", "place-suburb").isNotEmpty() } &&
                    compose.onAllNodesWithText(context.getString(R.string.map_loading)).fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithText(context.getString(R.string.map_load_error)).fetchSemanticsNodes().isEmpty()
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            nativeSnapshot("native-geometry-timeout")
            throw failure
        }
    }

    private fun longClickConsumers(): List<MapLibreMap.OnMapLongClickListener> {
        val detector = MapView::class.java.getDeclaredField("mapGestureDetector")
            .apply { isAccessible = true }.get(nativeView)
        val listeners = detector.javaClass.getDeclaredField("onMapLongClickListenerList")
            .apply { isAccessible = true }.get(detector) as Collection<*>
        return listeners.filterIsInstance<MapLibreMap.OnMapLongClickListener>().filter { it !== longClickWitness }
    }

    private fun nativeSnapshot(stage: String) {
        val loading = compose.onAllNodesWithText(context.getString(R.string.map_loading)).fetchSemanticsNodes().isNotEmpty()
        val diagnostic = onMain {
            val target = map.cameraPosition.target
            val visible = map.projection.visibleRegion.latLngBounds
            val detector = MapView::class.java.getDeclaredField("mapGestureDetector")
                .apply { isAccessible = true }.get(nativeView)
            val listeners = detector.javaClass.getDeclaredField("onMapLongClickListenerList")
                .apply { isAccessible = true }.get(detector) as Collection<*>
            val position = IntArray(2).also(nativeView::getLocationOnScreen)
            JSONObject().put("case", testName.methodName).put("stage", stage)
                .put("at_ms", SystemClock.elapsedRealtime()).put("frames", nativeFrames.get())
                .put("styles", nativeStyles.get()).put("raw_longclicks", nativeLongClicks.get())
                .put("longclick_listeners", listeners.size).put("witness_present", listeners.contains(longClickWitness))
                .put("application_consumers", longClickConsumers().size).put("loading_visible", loading)
                .put("attached", nativeView.isAttachedToWindow).put("shown", nativeView.isShown)
                .put("focus", nativeView.hasWindowFocus()).put("width", nativeView.width).put("height", nativeView.height)
                .put("view_left", position[0]).put("view_top", position[1])
                .put("style", map.style != null).put("zoom", map.cameraPosition.zoom)
                // Public bundled metadata bounds, actual camera/projection predicates; no raw location payload.
                .put("target_in_bundle", target != null && target.longitude in 138.4..139.0 && target.latitude in -35.2..-34.6)
                .put("viewport_overlaps_bundle", visible.longitudeEast >= 138.4 && visible.longitudeWest <= 139.0 &&
                    visible.latitudeNorth >= -35.2 && visible.latitudeSouth <= -34.6)
                .put("base_source", map.style?.getSource("aus-roads-tiles") != null)
                .put("roads", map.queryRenderedFeatures(RectF(0f, 0f, nativeView.width.toFloat(), nativeView.height.toFloat()),
                    "road-minor", "road-secondary", "road-primary", "road-motorway").size)
        }
        File(context.cacheDir, "navigation-native-${testName.methodName}.jsonl").appendText(diagnostic.toString() + "\n")
        println("NATIVE_NAVIGATION_DIAGNOSTIC $diagnostic")
    }

    private fun assertPersonalStateUnchanged(includeRoutes: Boolean) {
        runBlocking { preferences.forEach { (store, original) -> check(store.data.first() == original) } }
        tables.filter { includeRoutes || it.table == "pins" }.forEach {
            check(TableSnapshot.capture(context, it.databaseName, it.table) == it)
        } // Boolean checks intentionally avoid dumping private rows/preferences in failures.
    }

    // These service resources exist only in withNetwork; common test sources also
    // compile for offline. Lookup is fail-closed AFTER the network flavor guard.
    private fun networkText(name: String): String {
        check(BuildConfig.FLAVOR == "withNetwork")
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        check(id != 0) { "component=navigation-proof seam=service-resource class=not-joined" }
        return context.getString(id)
    }

    private fun navText(resource: Int): String = context.getString(resource)
    private fun stage(name: String) {
        println("NATIVE_NAVIGATION_STAGE case=${testName.methodName} flavor=${BuildConfig.FLAVOR} stage=$name source=controlled")
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private class ControlledRouteEngine : RoutingEngine {
        val requests = CopyOnWriteArrayList<RouteRequest>()
        @Volatile var result: RouteResult? = null
        override fun isReady() = true
        override suspend fun computeRoute(request: RouteRequest): RouteResult {
            requests += request
            return RouteResult(2_500, 300, listOf(request.origin,
                GeoPoint(longitude = (request.origin.longitude + request.destination.longitude) / 2,
                    latitude = (request.origin.latitude + request.destination.latitude) / 2), request.destination),
                emptyList()).also { result = it }
        }
    }

    private class ControlledGpsSource(private val original: DiagnosedNavigationLocationSource?,
        private val failCollections: Boolean) : DiagnosedNavigationLocationSource {
        val attempts = CopyOnWriteArrayList<Int>()
        val callbacks = CopyOnWriteArrayList<ProducerScope<NavigationLocation>>()
        val removals = AtomicInteger()
        val events = CopyOnWriteArrayList<Pair<NavigationGpsEvent, Int>>()
        override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> = locationUpdates(intervalMs, 1)
        override fun locationUpdates(intervalMs: Long, attempt: Int): Flow<NavigationLocation> = callbackFlow {
            attempts += attempt
            callbacks += this
            if (failCollections) close(SecurityException(PRIVATE_SOURCE_MESSAGE))
            awaitClose { removals.incrementAndGet() }
        }
        override fun recordGpsEvent(event: NavigationGpsEvent, attempt: Int) {
            events += event to attempt
            original?.recordGpsEvent(event, attempt) // Real provider callback/sink, not a test-written JSON witness.
        }
    }

    /** All typed columns/rows + autoincrement state, not just visible/20-most-recent history. */
    private data class TableSnapshot(val databaseName: String, val table: String, val version: Int,
        val columns: List<String>, val rows: List<List<Any?>>, val sequence: Long?) {
        fun restore(context: Context) {
            if (capture(context, databaseName, table) == this) return
            SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.beginTransaction()
                try {
                    check(database.version == version)
                    database.rawQuery("SELECT * FROM $table LIMIT 0", null).use { check(it.columnNames.toList() == columns) }
                    database.execSQL("DELETE FROM $table")
                    val names = columns.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
                    val placeholders = columns.joinToString(",") { "?" }
                    rows.forEach { row ->
                        val values = row.map { value ->
                            if (value is List<*>) value.map { it as Byte }.toByteArray() else value
                        }.toTypedArray()
                        database.execSQL("INSERT INTO $table ($names) VALUES ($placeholders)", values)
                    }
                    database.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf(table))
                    sequence?.let { database.execSQL("INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)", arrayOf(table, it)) }
                    database.setTransactionSuccessful()
                } finally { database.endTransaction() }
            }
            check(capture(context, databaseName, table) == this)
        }

        companion object {
            fun capture(context: Context, name: String, table: String): TableSnapshot {
                val path = context.getDatabasePath(name)
                check(path.isFile)
                return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                    database.rawQuery("SELECT * FROM $table ORDER BY id", null).use { cursor ->
                        val columns = cursor.columnNames.toList()
                        val rows = buildList {
                            while (cursor.moveToNext()) add(List(cursor.columnCount) { index ->
                                when (cursor.getType(index)) {
                                    Cursor.FIELD_TYPE_NULL -> null
                                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                                    else -> error("component=navigation-proof seam=row-type class=contract-drift")
                                }
                            })
                        }
                        val sequence = database.rawQuery("SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(table))
                            .use { if (it.moveToFirst()) it.getLong(0) else null }
                        TableSnapshot(name, table, database.version, columns, rows, sequence)
                    }
                }
            }
        }
    }

    private companion object {
        const val SERVICE_CLASS = "au.com.ausroads.navigation.NavigationForegroundService"
        const val PRIVATE_SOURCE_MESSAGE = "synthetic private provider payload -34.9,138.6 secret-query"
    }
}
