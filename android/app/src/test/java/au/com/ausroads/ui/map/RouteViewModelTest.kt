package au.com.ausroads.ui.map

import android.content.Context
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.CostingProfile
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class RouteViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeEngine: FakeRoutingEngine
    private lateinit var viewModel: RouteViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeEngine = FakeRoutingEngine()
        val context = mockk<Context>(relaxed = true)
        viewModel = RouteViewModel(context, fakeEngine)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() = runTest(testDispatcher) {
        assertThat(viewModel.routeState.value).isEqualTo(RouteUiState.Idle)
    }

    @Test
    fun `setOrigin and setDestination triggers route computation`() = runTest(testDispatcher) {
        val origin = GeoPoint(longitude = 138.6, latitude = -34.9)
        val destination = GeoPoint(longitude = 139.0, latitude = -35.0)

        viewModel.setOrigin(origin)
        viewModel.setDestination(destination)
        advanceUntilIdle()

        val state = viewModel.routeState.value
        assertThat(state).isInstanceOf(RouteUiState.Active::class.java)
        val active = state as RouteUiState.Active
        assertThat(active.result.distanceMeters).isEqualTo(5000)
        assertThat(active.result.durationSeconds).isEqualTo(600)
    }

    @Test
    fun `setOptions forwards avoid-options into the route request and recomputes`() =
        runTest(testDispatcher) {
            val origin = GeoPoint(longitude = 138.6, latitude = -34.9)
            val destination = GeoPoint(longitude = 139.0, latitude = -35.0)

            viewModel.setOrigin(origin)
            viewModel.setDestination(destination)
            advanceUntilIdle()
            // Baseline: defaults (all false) flow through.
            assertThat(fakeEngine.lastRequest?.options).isEqualTo(RouteOptions())

            viewModel.setOptions(RouteOptions(avoidTolls = true, avoidFerries = true))
            advanceUntilIdle()

            // The new options reach the engine and the route was recomputed.
            assertThat(fakeEngine.lastRequest?.options)
                .isEqualTo(RouteOptions(avoidTolls = true, avoidUnsealed = false, avoidFerries = true))
            assertThat(viewModel.routeState.value).isInstanceOf(RouteUiState.Active::class.java)
        }

    @Test
    fun `clearRoute resets to Idle`() = runTest(testDispatcher) {
        val origin = GeoPoint(longitude = 138.6, latitude = -34.9)
        val destination = GeoPoint(longitude = 139.0, latitude = -35.0)

        viewModel.setOrigin(origin)
        viewModel.setDestination(destination)
        advanceUntilIdle()
        assertThat(viewModel.routeState.value).isInstanceOf(RouteUiState.Active::class.java)

        viewModel.clearRoute()
        advanceUntilIdle()

        assertThat(viewModel.routeState.value).isEqualTo(RouteUiState.Idle)
        assertThat(viewModel.navigationSessionRoute.value).isNull()
    }

    @Test
    fun `navigation session keeps exact route while planned route is cleared`() =
        runTest(testDispatcher) {
            viewModel.setOrigin(GeoPoint(longitude = 138.6, latitude = -34.9))
            viewModel.setDestination(GeoPoint(longitude = 139.0, latitude = -35.0))
            advanceUntilIdle()
            val startedRoute = (viewModel.routeState.value as RouteUiState.Active).result

            viewModel.beginNavigationSession(startedRoute)
            viewModel.clearRoute()

            assertThat(viewModel.routeState.value).isEqualTo(RouteUiState.Idle)
            assertThat(viewModel.navigationSessionRoute.value).isSameInstanceAs(startedRoute)

            val stoppedRoute = viewModel.navigationSessionRoute.value
            viewModel.endNavigationSession()

            assertThat(stoppedRoute).isSameInstanceAs(startedRoute)
            assertThat(viewModel.navigationSessionRoute.value).isNull()
            assertThat(viewModel.routeState.value).isEqualTo(RouteUiState.Idle)
            viewModel.endNavigationSession()
            assertThat(viewModel.navigationSessionRoute.value).isNull()
        }

    @Test
    fun `navigation session owns map line and hides planned route sheet`() =
        runTest(testDispatcher) {
            viewModel.setOrigin(GeoPoint(longitude = 138.6, latitude = -34.9))
            viewModel.setDestination(GeoPoint(longitude = 139.0, latitude = -35.0))
            advanceUntilIdle()
            val startedRoute = (viewModel.routeState.value as RouteUiState.Active).result

            val plannedSurfaces = routeSurfaceState(viewModel.routeState.value, null)
            assertThat(plannedSurfaces.mapRoute).isSameInstanceAs(startedRoute)
            assertThat(plannedSurfaces.sheetRoute).isSameInstanceAs(startedRoute)

            viewModel.beginNavigationSession(startedRoute)
            viewModel.setDestination(GeoPoint(longitude = 138.7, latitude = -34.8))
            advanceUntilIdle()
            val recomputedRoute = (viewModel.routeState.value as RouteUiState.Active).result
            val navigatingSurfaces = routeSurfaceState(
                routeState = viewModel.routeState.value,
                navigationSessionRoute = viewModel.navigationSessionRoute.value,
            )

            assertThat(recomputedRoute.geometry.last())
                .isEqualTo(GeoPoint(longitude = 138.7, latitude = -34.8))
            assertThat(navigatingSurfaces.mapRoute).isSameInstanceAs(startedRoute)
            assertThat(navigatingSurfaces.sheetRoute).isNull()
            assertThat(viewModel.navigationSessionRoute.value).isSameInstanceAs(startedRoute)
            viewModel.endNavigationSession()
        }

    @Test
    fun `engine unavailable offers map-pack recovery`() = runTest(testDispatcher) {
        fakeEngine.ready = false

        viewModel.setOrigin(GeoPoint(longitude = 138.6, latitude = -34.9))
        viewModel.setDestination(GeoPoint(longitude = 139.0, latitude = -35.0))

        val state = viewModel.routeState.value as RouteUiState.Error
        assertThat(state.recovery).isEqualTo(RouteRecovery.MANAGE_MAP_PACK)
        assertThat(fakeEngine.computeCalls).isEqualTo(0)
    }

    @Test
    fun `retry reuses retained route request after a recoverable failure`() =
        runTest(testDispatcher) {
            fakeEngine.failure = IllegalStateException("temporary engine fault")
            viewModel.setOrigin(GeoPoint(longitude = 138.6, latitude = -34.9))
            viewModel.setDestination(GeoPoint(longitude = 139.0, latitude = -35.0))
            advanceUntilIdle()

            val failed = viewModel.routeState.value as RouteUiState.Error
            assertThat(failed.recovery).isEqualTo(RouteRecovery.RETRY)

            fakeEngine.failure = null
            viewModel.retry()
            advanceUntilIdle()

            assertThat(viewModel.routeState.value).isInstanceOf(RouteUiState.Active::class.java)
            assertThat(fakeEngine.computeCalls).isEqualTo(2)
        }

    @Test
    fun `clearRoute owns Idle even when an in-flight engine call completes later`() =
        runTest(testDispatcher) {
            val controlledEngine = ControlledRoutingEngine()
            val controlledViewModel = RouteViewModel(mockk(relaxed = true), controlledEngine)
            controlledViewModel.setOrigin(GeoPoint(longitude = 138.6, latitude = -34.9))
            controlledViewModel.setDestination(GeoPoint(longitude = 139.0, latitude = -35.0))
            runCurrent()

            assertThat(controlledViewModel.routeState.value).isEqualTo(RouteUiState.Loading)
            assertThat(controlledEngine.pendingCount).isEqualTo(1)

            controlledViewModel.clearRoute()
            assertThat(controlledViewModel.routeState.value).isEqualTo(RouteUiState.Idle)

            controlledEngine.complete(index = 0, distanceMeters = 5_000)
            runCurrent()

            assertThat(controlledViewModel.routeState.value).isEqualTo(RouteUiState.Idle)
        }

    @Test
    fun `newer destination options and retry own state when older calls complete last`() =
        runTest(testDispatcher) {
            val controlledEngine = ControlledRoutingEngine()
            val controlledViewModel = RouteViewModel(mockk(relaxed = true), controlledEngine)
            val firstDestination = GeoPoint(longitude = 139.0, latitude = -35.0)
            val secondDestination = GeoPoint(longitude = 138.7, latitude = -34.8)
            val updatedOptions = RouteOptions(avoidTolls = true)
            controlledViewModel.setOrigin(GeoPoint(longitude = 138.6, latitude = -34.9))
            controlledViewModel.setDestination(firstDestination)
            runCurrent()
            controlledViewModel.setDestination(secondDestination)
            runCurrent()
            controlledViewModel.setOptions(updatedOptions)
            runCurrent()
            controlledViewModel.retry()
            runCurrent()

            assertThat(controlledEngine.pendingCount).isEqualTo(4)
            assertThat(controlledEngine.requestAt(1).destination).isEqualTo(secondDestination)
            assertThat(controlledEngine.requestAt(2).options).isEqualTo(updatedOptions)
            assertThat(controlledEngine.requestAt(3).options).isEqualTo(updatedOptions)

            controlledEngine.complete(index = 3, distanceMeters = 2_000)
            runCurrent()
            assertThat((controlledViewModel.routeState.value as RouteUiState.Active).result.distanceMeters)
                .isEqualTo(2_000)

            controlledEngine.complete(index = 2, distanceMeters = 3_000)
            controlledEngine.complete(index = 1, distanceMeters = 4_000)
            controlledEngine.complete(index = 0, distanceMeters = 5_000)
            runCurrent()
            assertThat((controlledViewModel.routeState.value as RouteUiState.Active).result.distanceMeters)
                .isEqualTo(2_000)
        }

    private class FakeRoutingEngine : RoutingEngine {
        var lastRequest: RouteRequest? = null
        var ready: Boolean = true
        var failure: Exception? = null
        var computeCalls: Int = 0

        override suspend fun computeRoute(request: RouteRequest): RouteResult {
            computeCalls++
            failure?.let { throw it }
            lastRequest = request
            return RouteResult(
                distanceMeters = 5000,
                durationSeconds = 600,
                geometry = listOf(request.origin, request.destination),
                maneuvers = listOf(
                    Maneuver(
                        instruction = "Head north",
                        lengthMeters = 5000,
                        durationSeconds = 600,
                        beginShapeIndex = 0,
                        streetName = null,
                        maneuverType = "depart",
                    ),
                ),
            )
        }

        override fun isReady(): Boolean = ready
    }

    /**
     * Deliberately ignores coroutine cancellation by suspending with a raw continuation.
     * This models an engine/native boundary that can return after its caller was cancelled.
     */
    private class ControlledRoutingEngine : RoutingEngine {
        private data class PendingCall(
            val request: RouteRequest,
            val continuation: Continuation<RouteResult>,
        )

        private val pendingCalls = mutableListOf<PendingCall>()

        val pendingCount: Int
            get() = pendingCalls.size

        fun requestAt(index: Int): RouteRequest = pendingCalls[index].request

        fun complete(index: Int, distanceMeters: Int) {
            val call = pendingCalls[index]
            call.continuation.resume(
                RouteResult(
                    distanceMeters = distanceMeters,
                    durationSeconds = distanceMeters / 10,
                    geometry = listOf(call.request.origin, call.request.destination),
                    maneuvers = emptyList(),
                )
            )
        }

        override suspend fun computeRoute(request: RouteRequest): RouteResult =
            suspendCoroutine { continuation ->
                pendingCalls += PendingCall(request, continuation)
            }

        override fun isReady(): Boolean = true
    }
}
