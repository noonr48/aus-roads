package au.com.ausroads.ui.map

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.data.routes.RouteHistoryDao
import au.com.ausroads.data.routes.RouteHistoryEntity
import au.com.ausroads.feature.navigation.NavigationLocation
import au.com.ausroads.feature.navigation.NavigationLocationSource
import au.com.ausroads.feature.navigation.NavigationState
import au.com.ausroads.feature.navigation.NavigationViewModel
import au.com.ausroads.navigation.tts.NavigationTts
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import au.com.ausroads.ui.designsystem.AusRoadsTheme
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

@RunWith(AndroidJUnit4::class)
class RouteOwnershipLifecycleInstrumentedTest {
    // A blank host keeps this test to one native MapView. Launching MainActivity
    // first would create the production map before setContent replaces it.
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun grantPreciseLocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.grantRuntimePermission(
            packageName,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            packageName,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    @Test
    fun startRetainsExactRouteLifecycleRestartsOneSubscriptionAndStopSavesThenClears() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val routingEngine = FixedRoutingEngine(EXACT_ROUTE)
        val locationSource = CountingLocationSource()
        val historyDao = CapturingRouteHistoryDao()
        val routeViewModel = RouteViewModel(context, routingEngine)
        val navigationViewModel = NavigationViewModel(
            context = context,
            locationProvider = locationSource,
            tts = mockk<NavigationTts>(relaxed = true),
            routingEngine = routingEngine,
        ).also { it.setTtsEnabled(false) }
        val historyViewModel = RouteHistoryViewModel(historyDao)

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                AusRoadsTheme {
                    MapScreen(
                        onOpenSettings = {},
                        navigationViewModel = navigationViewModel,
                        routeHistoryViewModel = historyViewModel,
                        routeViewModel = routeViewModel,
                        showAttribution = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            routeViewModel.setOrigin(EXACT_ROUTE.geometry.first())
            routeViewModel.setDestination(EXACT_ROUTE.geometry.last())
        }

        composeRule.waitUntil(UI_TIMEOUT_MS) {
            routeViewModel.routeState.value == RouteUiState.Active(EXACT_ROUTE)
        }
        composeRule.onNodeWithText(START_NAVIGATION)
            .performScrollTo()
            .assertIsDisplayed()
        awaitRouteStyle(RouteStyleState(sourcePresent = true, layerPresent = true))

        composeRule.onNodeWithText(START_NAVIGATION).performClick()
        composeRule.waitUntil(UI_TIMEOUT_MS) {
            routeViewModel.navigationSessionRoute.value === EXACT_ROUTE &&
                navigationViewModel.state.value is NavigationState.Navigating
        }

        composeRule.onNodeWithText(START_NAVIGATION).assertDoesNotExist()
        composeRule.onNodeWithText(CLOSE_ROUTE).assertDoesNotExist()
        composeRule.onNodeWithText(STOP_NAVIGATION).assertIsDisplayed()
        assertThat(routeViewModel.navigationSessionRoute.value).isSameInstanceAs(EXACT_ROUTE)
        awaitRouteStyle(RouteStyleState(sourcePresent = true, layerPresent = true))
        awaitCondition("one active location subscription") {
            locationSource.subscriptionStarts.get() == 1 &&
                locationSource.activeSubscriptions.get() == 1
        }

        locationSource.publish(
            NavigationLocation(
                latitude = EXACT_ROUTE.geometry[1].latitude,
                longitude = EXACT_ROUTE.geometry[1].longitude,
                speedKmh = RETAINED_SPEED_KMH,
                bearing = 90f,
                accuracyMeters = 3f,
                timestamp = System.currentTimeMillis(),
            ),
        )
        composeRule.waitUntil(UI_TIMEOUT_MS) {
            (navigationViewModel.state.value as? NavigationState.Navigating)
                ?.currentSpeedKmh == RETAINED_SPEED_KMH
        }
        val retainedProgress = navigationViewModel.state.value

        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        awaitCondition("location subscription paused while the host is stopped") {
            locationSource.activeSubscriptions.get() == 0
        }
        assertThat(navigationViewModel.state.value).isEqualTo(retainedProgress)

        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        awaitCondition("exactly one replacement subscription after resume") {
            locationSource.subscriptionStarts.get() == 2 &&
                locationSource.activeSubscriptions.get() == 1
        }
        assertThat(navigationViewModel.state.value).isEqualTo(retainedProgress)
        awaitRouteStyle(RouteStyleState(sourcePresent = true, layerPresent = true))

        composeRule.onNodeWithText(STOP_NAVIGATION).assertIsDisplayed().performClick()
        assertThat(historyDao.inserted.await(UI_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue()

        val savedRoute = checkNotNull(historyDao.lastInserted.get())
        assertThat(savedRoute.originLat).isEqualTo(EXACT_ROUTE.geometry.first().latitude)
        assertThat(savedRoute.originLon).isEqualTo(EXACT_ROUTE.geometry.first().longitude)
        assertThat(savedRoute.destLat).isEqualTo(EXACT_ROUTE.geometry.last().latitude)
        assertThat(savedRoute.destLon).isEqualTo(EXACT_ROUTE.geometry.last().longitude)
        assertThat(savedRoute.distanceMeters).isEqualTo(EXACT_ROUTE.distanceMeters)
        assertThat(savedRoute.durationSeconds).isEqualTo(EXACT_ROUTE.durationSeconds)

        composeRule.waitUntil(UI_TIMEOUT_MS) {
            routeViewModel.navigationSessionRoute.value == null &&
                routeViewModel.routeState.value == RouteUiState.Idle &&
                navigationViewModel.state.value == NavigationState.Idle &&
                locationSource.subscriptionStarts.get() == 2 &&
                locationSource.activeSubscriptions.get() == 0
        }
        composeRule.waitForIdle()
        awaitRouteStyle(RouteStyleState(sourcePresent = false, layerPresent = false))
    }

    private fun awaitRouteStyle(expected: RouteStyleState) {
        val deadline = SystemClock.uptimeMillis() + MAP_READY_TIMEOUT_MS
        var observed: RouteStyleState? = null
        while (SystemClock.uptimeMillis() < deadline) {
            observed = readRouteStyle()
            if (observed == expected) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Expected route style $expected but last observed $observed")
    }

    private fun readRouteStyle(): RouteStyleState {
        val styleReady = CountDownLatch(1)
        val result = AtomicReference<RouteStyleState?>()
        val callbackFailure = AtomicReference<Throwable?>()

        composeRule.runOnUiThread {
            val mapView = checkNotNull(findMapView(composeRule.activity.window.decorView)) {
                "MapView is not attached to the activity hierarchy"
            }
            mapView.getMapAsync { map ->
                map.getStyle { style ->
                    runCatching {
                        result.set(
                            RouteStyleState(
                                sourcePresent = style.getSource(ROUTE_STYLE_ID) != null,
                                layerPresent = style.getLayer(ROUTE_STYLE_ID) != null,
                            ),
                        )
                    }.onFailure(callbackFailure::set)
                    styleReady.countDown()
                }
            }
        }

        check(styleReady.await(STYLE_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "MapLibre style callback timed out"
        }
        callbackFailure.get()?.let { throw AssertionError("MapLibre style callback failed", it) }
        return checkNotNull(result.get())
    }

    private fun findMapView(view: View): MapView? {
        if (view is MapView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findMapView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private data class RouteStyleState(
        val sourcePresent: Boolean,
        val layerPresent: Boolean,
    )

    private class FixedRoutingEngine(
        private val result: RouteResult,
    ) : RoutingEngine {
        override suspend fun computeRoute(request: RouteRequest): RouteResult = result

        override fun isReady(): Boolean = true
    }

    private class CountingLocationSource : NavigationLocationSource {
        private val updates = MutableSharedFlow<NavigationLocation>(extraBufferCapacity = 8)
        val subscriptionStarts = AtomicInteger()
        val activeSubscriptions = AtomicInteger()

        override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> = flow {
            subscriptionStarts.incrementAndGet()
            activeSubscriptions.incrementAndGet()
            try {
                updates.collect { location -> emit(location) }
            } finally {
                activeSubscriptions.decrementAndGet()
            }
        }

        fun publish(location: NavigationLocation) {
            check(updates.tryEmit(location)) { "Location test buffer is full" }
        }
    }

    private class CapturingRouteHistoryDao : RouteHistoryDao {
        val inserted = CountDownLatch(1)
        val lastInserted = AtomicReference<RouteHistoryEntity?>()

        override fun observeRecent(limit: Int): Flow<List<RouteHistoryEntity>> = flowOf(emptyList())

        override suspend fun insert(route: RouteHistoryEntity) {
            lastInserted.set(route)
            inserted.countDown()
        }

        override suspend fun deleteById(routeId: Long): Int = 1

        override suspend fun clearAll() = Unit
    }

    private companion object {
        const val START_NAVIGATION = "Start navigation"
        const val STOP_NAVIGATION = "Stop navigation"
        const val CLOSE_ROUTE = "Close route"
        const val ROUTE_STYLE_ID = "route-line"
        const val RETAINED_SPEED_KMH = 37.0
        const val UI_TIMEOUT_MS = 10_000L
        const val MAP_READY_TIMEOUT_MS = 20_000L
        const val STYLE_CALLBACK_TIMEOUT_MS = 2_000L
        const val POLL_INTERVAL_MS = 100L

        val EXACT_ROUTE = RouteResult(
            distanceMeters = 1_234,
            durationSeconds = 567,
            geometry = listOf(
                GeoPoint(longitude = 138.6007, latitude = -34.9285),
                GeoPoint(longitude = 138.6040, latitude = -34.9270),
                GeoPoint(longitude = 138.6080, latitude = -34.9250),
            ),
            maneuvers = emptyList(),
        )
    }
}
