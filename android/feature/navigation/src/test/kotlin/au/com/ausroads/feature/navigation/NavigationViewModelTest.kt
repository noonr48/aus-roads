package au.com.ausroads.feature.navigation

import android.content.Context
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.navigation.tts.NavigationTts
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeRoutingEngine(recalculatedRoute))

        assertThat(viewModel.state.value).isEqualTo(NavigationState.Idle)
    }

    @Test
    fun `successful off-route recalculation replaces guidance`() = runTest(testDispatcher) {
        val engine = FakeRoutingEngine(recalculatedRoute)
        val viewModel = viewModel(engine)

        startAndMoveOffRoute(viewModel)
        assertThat(viewModel.state.value).isInstanceOf(NavigationState.Recalculating::class.java)
        advanceUntilIdle()

        val state = viewModel.state.value as NavigationState.Navigating
        assertThat(state.currentManeuver?.instruction).isEqualTo("Take recovered route")
        assertThat(state.remainingDistanceMeters).isEqualTo(recalculatedRoute.distanceMeters.toDouble())
        assertThat(engine.requests).hasSize(1)
    }

    @Test
    fun `failed recalculation is visible with prior guidance when TTS is off`() =
        runTest(testDispatcher) {
            val engine = FakeRoutingEngine(recalculatedRoute).apply {
                failure = IllegalStateException("routing data temporarily unavailable")
            }
            val tts = mockk<NavigationTts>(relaxed = true)
            val viewModel = viewModel(engine, tts)

            startAndMoveOffRoute(viewModel)
            advanceUntilIdle()

            val state = viewModel.state.value as NavigationState.RecalculationFailed
            assertThat(state.previousState.currentManeuver?.instruction).isEqualTo("Continue on old route")
            assertThat(state.destination).isEqualTo(initialRoute.geometry.last())
            verify(exactly = 0) { tts.speakText(any()) }
        }

    @Test
    fun `retry uses latest position and recovers after a recalculation failure`() =
        runTest(testDispatcher) {
            val engine = FakeRoutingEngine(recalculatedRoute).apply {
                failure = IllegalStateException("first attempt failed")
            }
            val viewModel = viewModel(engine)

            startAndMoveOffRoute(viewModel)
            advanceUntilIdle()
            assertThat(viewModel.state.value).isInstanceOf(
                NavigationState.RecalculationFailed::class.java,
            )

            val latestPosition = point(latitude = -34.9300, longitude = 138.6600)
            viewModel.updatePosition(latestPosition, speedKmh = 48.0)
            engine.failure = null
            viewModel.retryRecalculation()
            assertThat(viewModel.state.value).isInstanceOf(NavigationState.Recalculating::class.java)
            advanceUntilIdle()

            val state = viewModel.state.value as NavigationState.Navigating
            assertThat(state.currentManeuver?.instruction).isEqualTo("Take recovered route")
            assertThat(engine.requests).hasSize(2)
            assertThat(engine.requests.last().origin).isEqualTo(latestPosition)
            assertThat(engine.requests.last().destination).isEqualTo(initialRoute.geometry.last())
        }

    @Test
    fun `stale recalculation cannot replace a newly started navigation`() =
        runTest(testDispatcher) {
            val engine = NonCooperativeRoutingEngine(recalculatedRoute)
            val viewModel = viewModel(engine)

            startAndMoveOffRoute(viewModel)
            runCurrent()
            assertThat(engine.started.isCompleted).isTrue()

            assertThat(viewModel.startNavigation(replacementRoute)).isTrue()
            advanceUntilIdle()

            assertThat(engine.ignoredCancellation).isTrue()
            val state = viewModel.state.value as NavigationState.Navigating
            assertThat(state.currentManeuver?.instruction).isEqualTo("Follow replacement route")
            assertThat(state.remainingDistanceMeters).isEqualTo(replacementRoute.distanceMeters.toDouble())
        }

    @Test
    fun `permission-rejected replacement invalidates the old recalculation`() =
        runTest(testDispatcher) {
            var preciseLocationGranted = true
            val engine = NonCooperativeRoutingEngine(recalculatedRoute)
            val viewModel = viewModel(
                engine = engine,
                preciseLocationPermissionChecker = { preciseLocationGranted },
            )

            startAndMoveOffRoute(viewModel)
            runCurrent()
            assertThat(engine.started.isCompleted).isTrue()

            preciseLocationGranted = false
            assertThat(viewModel.startNavigation(replacementRoute)).isFalse()
            advanceUntilIdle()

            assertThat(engine.ignoredCancellation).isTrue()
            assertThat(viewModel.state.value).isEqualTo(NavigationState.PreciseLocationRequired)
        }

    @Test
    fun `stale recalculation cannot resume navigation after stop`() = runTest(testDispatcher) {
        val engine = NonCooperativeRoutingEngine(recalculatedRoute)
        val viewModel = viewModel(engine)

        startAndMoveOffRoute(viewModel)
        runCurrent()
        assertThat(engine.started.isCompleted).isTrue()

        viewModel.stopNavigation()
        advanceUntilIdle()

        assertThat(engine.ignoredCancellation).isTrue()
        assertThat(viewModel.state.value).isEqualTo(NavigationState.Idle)
    }

    @Test
    fun `location subscription pauses on stop and resumes once with progress retained`() =
        runTest(testDispatcher) {
            val locationSource = CountingLocationSource()
            val viewModel = viewModel(
                engine = FakeRoutingEngine(recalculatedRoute),
                locationSource = locationSource,
            )

            viewModel.onHostStarted()
            assertThat(viewModel.startNavigation(initialRoute)).isTrue()
            runCurrent()
            assertThat(locationSource.subscriptionStarts).isEqualTo(1)
            assertThat(locationSource.activeSubscriptions).isEqualTo(1)

            viewModel.updatePosition(onRoutePosition, speedKmh = 37.0)
            val retainedProgress = viewModel.state.value
            viewModel.onHostStopped()
            runCurrent()

            assertThat(locationSource.activeSubscriptions).isEqualTo(0)
            assertThat(viewModel.state.value).isEqualTo(retainedProgress)

            viewModel.onHostStarted()
            viewModel.onHostStarted()
            runCurrent()

            assertThat(locationSource.subscriptionStarts).isEqualTo(2)
            assertThat(locationSource.activeSubscriptions).isEqualTo(1)
            assertThat(viewModel.state.value).isEqualTo(retainedProgress)

            viewModel.stopNavigation()
            runCurrent()
            assertThat(locationSource.activeSubscriptions).isEqualTo(0)
        }

    @Test
    fun `explicit stop while backgrounded prevents lifecycle restart`() = runTest(testDispatcher) {
        val locationSource = CountingLocationSource()
        val viewModel = viewModel(
            engine = FakeRoutingEngine(recalculatedRoute),
            locationSource = locationSource,
        )

        viewModel.onHostStarted()
        assertThat(viewModel.startNavigation(initialRoute)).isTrue()
        runCurrent()
        viewModel.onHostStopped()
        runCurrent()
        assertThat(locationSource.activeSubscriptions).isEqualTo(0)

        viewModel.stopNavigation()
        viewModel.onHostStarted()
        runCurrent()

        assertThat(viewModel.state.value).isEqualTo(NavigationState.Idle)
        assertThat(locationSource.subscriptionStarts).isEqualTo(1)
        assertThat(locationSource.activeSubscriptions).isEqualTo(0)
    }

    @Test
    fun `late callback from paused subscription cannot replace resumed progress`() =
        runTest(testDispatcher) {
            val locationSource = NonCooperativeLocationSource()
            val viewModel = viewModel(
                engine = FakeRoutingEngine(recalculatedRoute),
                locationSource = locationSource,
            )

            viewModel.onHostStarted()
            assertThat(viewModel.startNavigation(initialRoute)).isTrue()
            runCurrent()
            viewModel.onHostStopped()
            runCurrent()
            viewModel.onHostStarted()
            runCurrent()
            assertThat(locationSource.subscriptionStarts).isEqualTo(2)

            locationSource.emitFromSubscription(1, onRoutePosition.toNavigationLocation(37.0))
            val resumedProgress = viewModel.state.value
            locationSource.emitFromSubscription(
                0,
                initialRoute.geometry.last().toNavigationLocation(speedKmh = 0.0),
            )

            assertThat(viewModel.state.value).isEqualTo(resumedProgress)

            viewModel.stopNavigation()
            runCurrent()
        }

    private fun viewModel(
        engine: RoutingEngine,
        tts: NavigationTts = mockk(relaxed = true),
        preciseLocationPermissionChecker: () -> Boolean = { true },
        locationSource: NavigationLocationSource = EmptyLocationSource,
    ): NavigationViewModel = NavigationViewModel(
        context = mockk<Context>(relaxed = true),
        locationProvider = locationSource,
        tts = tts,
        routingEngine = engine,
        preciseLocationPermissionChecker = preciseLocationPermissionChecker,
    ).also { it.setTtsEnabled(false) }

    private fun startAndMoveOffRoute(viewModel: NavigationViewModel) {
        assertThat(viewModel.startNavigation(initialRoute)).isTrue()
        viewModel.updatePosition(offRoutePosition, speedKmh = 52.0)
    }

    private object EmptyLocationSource : NavigationLocationSource {
        override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> = emptyFlow()
    }

    private class CountingLocationSource : NavigationLocationSource {
        var subscriptionStarts = 0
            private set
        var activeSubscriptions = 0
            private set

        override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> = callbackFlow {
            subscriptionStarts++
            activeSubscriptions++
            awaitClose { activeSubscriptions-- }
        }
    }

    private class NonCooperativeLocationSource : NavigationLocationSource {
        private val collectors = mutableListOf<FlowCollector<NavigationLocation>>()
        var activeSubscriptions = 0
            private set
        val subscriptionStarts: Int
            get() = collectors.size

        override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> =
            object : Flow<NavigationLocation> {
                override suspend fun collect(collector: FlowCollector<NavigationLocation>) {
                    collectors += collector
                    activeSubscriptions++
                    try {
                        awaitCancellation()
                    } finally {
                        activeSubscriptions--
                    }
                }
            }

        suspend fun emitFromSubscription(index: Int, location: NavigationLocation) {
            withContext(NonCancellable) {
                collectors[index].emit(location)
            }
        }
    }

    private class FakeRoutingEngine(
        var result: RouteResult,
    ) : RoutingEngine {
        var failure: Throwable? = null
        val requests = mutableListOf<RouteRequest>()

        override suspend fun computeRoute(request: RouteRequest): RouteResult {
            requests += request
            failure?.let { throw it }
            return result
        }

        override fun isReady(): Boolean = true
    }

    private class NonCooperativeRoutingEngine(
        private val result: RouteResult,
    ) : RoutingEngine {
        val started = CompletableDeferred<Unit>()
        var ignoredCancellation = false
            private set

        override suspend fun computeRoute(request: RouteRequest): RouteResult {
            started.complete(Unit)
            return try {
                awaitCancellation()
            } catch (_: CancellationException) {
                ignoredCancellation = true
                result
            }
        }

        override fun isReady(): Boolean = true
    }

    private companion object {
        fun point(latitude: Double, longitude: Double) = GeoPoint(
            longitude = longitude,
            latitude = latitude,
        )

        fun maneuver(instruction: String) = Maneuver(
            instruction = instruction,
            streetName = null,
            lengthMeters = 1_000,
            durationSeconds = 600,
            maneuverType = "continue",
            beginShapeIndex = 0,
        )

        val initialRoute = RouteResult(
            distanceMeters = 1_000,
            durationSeconds = 600,
            geometry = listOf(
                point(latitude = -34.9285, longitude = 138.6007),
                point(latitude = -34.9385, longitude = 138.6007),
            ),
            maneuvers = listOf(maneuver("Continue on old route")),
        )
        val offRoutePosition = point(latitude = -34.9285, longitude = 138.6500)
        val onRoutePosition = initialRoute.geometry.first()
        val recalculatedRoute = RouteResult(
            distanceMeters = 1_250,
            durationSeconds = 720,
            geometry = listOf(offRoutePosition, initialRoute.geometry.last()),
            maneuvers = listOf(maneuver("Take recovered route")),
        )
        val replacementRoute = RouteResult(
            distanceMeters = 2_000,
            durationSeconds = 900,
            geometry = listOf(
                point(latitude = -34.9000, longitude = 138.6200),
                point(latitude = -34.9200, longitude = 138.6400),
            ),
            maneuvers = listOf(maneuver("Follow replacement route")),
        )

        fun GeoPoint.toNavigationLocation(speedKmh: Double) = NavigationLocation(
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            bearing = 0f,
            accuracyMeters = 3f,
            timestamp = 1L,
        )
    }
}
