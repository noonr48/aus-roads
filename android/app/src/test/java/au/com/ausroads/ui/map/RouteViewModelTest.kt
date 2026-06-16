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
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

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
    }

    private class FakeRoutingEngine : RoutingEngine {
        var lastRequest: RouteRequest? = null

        override suspend fun computeRoute(request: RouteRequest): RouteResult {
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

        override fun isReady(): Boolean = true
    }
}
