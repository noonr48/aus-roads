package au.com.ausroads.feature.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.navigation.tts.NavigationTts
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val tts = mockk<NavigationTts>(relaxed = true)
    private val routing = mockk<RoutingEngine>(relaxed = true)
    private val hazards = mockk<RoadHazardSource>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED
        // Existing Android clock pattern, tied to runTest's monotonic scheduler.
        // Never mock java.lang.System: that poisons executor/coroutine internals.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { dispatcher.scheduler.currentTime }
        coEvery { hazards.contextAt(any(), any(), any()) } returns RoadHazardContext(null, emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `initial state is Idle`() = runTest(dispatcher) {
        withNavigation(ScriptedSource { flow { awaitCancellation() } }) { vm ->
            assertThat(vm.state.value).isEqualTo(NavigationState.Idle)
        }
    }

    @Test
    fun `Navigating state holds maneuver data`() {
        val maneuver = Maneuver(
            instruction = "Turn left",
            streetName = "King William St",
            lengthMeters = 500,
            durationSeconds = 60,
            maneuverType = "turn",
            beginShapeIndex = 0,
        )
        val state = NavigationState.Navigating(
            currentManeuver = maneuver,
            nextManeuver = null,
            remainingDistanceMeters = 5000.0,
            remainingDurationSeconds = 300.0,
            maneuverIndex = 0,
            totalManeuvers = 5,
        )
        assertThat(state.currentManeuver?.instruction).isEqualTo("Turn left")
        assertThat(state.remainingDistanceMeters).isEqualTo(5000.0)
    }

    @Test
    fun `Arrived state is terminal`() {
        assertThat(NavigationState.Arrived).isEqualTo(NavigationState.Arrived)
    }

    @Test
    fun `collection failures retry only at bounded backoff deadlines and expose exhaustion`() = runTest(dispatcher) {
        val source = ScriptedSource { flow { throw IllegalStateException("private provider payload") } }
        withNavigation(source) { vm ->
            vm.startNavigation(sampleRoute())
            runCurrent()
            assertThat(source.attempts).containsExactly(1)
            advanceTimeBy(1_999)
            runCurrent()
            assertThat(source.attempts).containsExactly(1)
            advanceTimeBy(1)
            runCurrent()
            assertThat(source.attempts).containsExactly(1, 2).inOrder()
            advanceTimeBy(3_999)
            runCurrent()
            assertThat(source.attempts).hasSize(2)
            advanceTimeBy(1)
            runCurrent()
            assertThat(source.attempts).containsExactly(1, 2, 3).inOrder()
            advanceTimeBy(5_999)
            runCurrent()
            assertThat(source.attempts).hasSize(3)
            advanceTimeBy(1)
            runCurrent()
            assertThat(source.attempts).containsExactly(1, 2, 3, 4).inOrder()
            assertThat(source.events.filter { it.first == NavigationGpsEvent.COLLECTION_FAILED }.map { it.second })
                .containsExactly(1, 2, 3, 4).inOrder()
            assertThat(source.events.count { it == (NavigationGpsEvent.RETRIES_EXHAUSTED to 4) }).isEqualTo(1)
            assertNoInventedFix(vm)
            assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isTrue()
            advanceTimeBy(60_000)
            runCurrent()
            assertThat(source.attempts).hasSize(4)
            verify(exactly = 0) { tts.speakArrival() }
        }
    }

    @Test
    fun `cold registration without a fix becomes lost at the monotonic deadline once`() = runTest(dispatcher) {
        val source = ScriptedSource { flow { awaitCancellation() } }
        withNavigation(source) { vm ->
            vm.startNavigation(sampleRoute())
            runCurrent()
            advanceTimeBy(NavigationViewModel.GPS_STALE_MS - 1)
            runCurrent()
            assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isFalse()
            advanceTimeBy(1)
            runCurrent()
            assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isTrue()
            assertNoInventedFix(vm)
            assertThat(source.events).contains(NavigationGpsEvent.NO_FIX to 1)
            advanceTimeBy(30_000)
            runCurrent()
            assertThat(source.events.count { it.first == NavigationGpsEvent.NO_FIX }).isEqualTo(1)
            assertThat(source.attempts).containsExactly(1)
        }
    }

    @Test
    fun `a real first fix clears cold loss and later silence becomes stale`() = runTest(dispatcher) {
        val fixes = Channel<NavigationLocation>(Channel.UNLIMITED)
        val source = ScriptedSource { fixes.receiveAsFlow() }
        withNavigation(source) { vm ->
            vm.startNavigation(sampleRoute())
            runCurrent()
            advanceTimeBy(NavigationViewModel.GPS_STALE_MS)
            runCurrent()
            assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isTrue()
            fixes.send(sampleFix())
            runCurrent()
            val fixed = vm.state.value as NavigationState.Navigating
            assertThat(fixed.awaitingGpsFix).isFalse()
            assertThat(fixed.gpsLost).isFalse()
            assertThat(fixed.currentSpeedKmh).isEqualTo(36.0)
            advanceTimeBy(NavigationViewModel.GPS_STALE_MS - 1)
            runCurrent()
            assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isFalse()
            advanceTimeBy(1)
            runCurrent()
            assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isTrue()
            assertThat(source.events).contains(NavigationGpsEvent.FIX_STALE to 1)
        }
        fixes.close()
    }

    @Test
    fun `a fix at monotonic zero is not mistaken for an absent first fix`() = runTest(dispatcher) {
        val source = ScriptedSource { flow { emit(sampleFix()); awaitCancellation() } }
        withNavigation(source) { vm ->
            vm.startNavigation(sampleRoute())
            runCurrent()
            assertThat((vm.state.value as NavigationState.Navigating).awaitingGpsFix).isFalse()
            advanceTimeBy(NavigationViewModel.GPS_STALE_MS)
            runCurrent()
            assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isTrue()
            assertThat(source.events).contains(NavigationGpsEvent.FIX_STALE to 1)
            assertThat(source.events.none { it.first == NavigationGpsEvent.NO_FIX }).isTrue()
        }
    }

    @Test
    fun `Stop during retry backoff prevents later subscription or exhaustion`() = runTest(dispatcher) {
        val source = ScriptedSource { flow { throw IllegalStateException("provider failed") } }
        withNavigation(source) { vm ->
            vm.startNavigation(sampleRoute())
            runCurrent()
            vm.stopNavigation()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(NavigationState.Idle)
            assertThat(source.attempts).containsExactly(1)
            assertThat(source.events.none { it.first == NavigationGpsEvent.RETRIES_EXHAUSTED }).isTrue()
        }
    }

    @Test
    fun `Stop cleans a live collection and rejects late callback publication`() = runTest(dispatcher) {
        var removed = 0
        lateinit var producer: ProducerScope<NavigationLocation>
        val source = ScriptedSource {
            callbackFlow {
                producer = this
                awaitClose { removed++ }
            }
        }
        withNavigation(source) { vm ->
            vm.startNavigation(sampleRoute())
            runCurrent()
            vm.stopNavigation()
            runCurrent()
            assertThat(removed).isEqualTo(1)
            assertThat(producer.trySend(sampleFix()).isFailure).isTrue()
            advanceTimeBy(60_000)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(NavigationState.Idle)
            assertThat(source.attempts).containsExactly(1)
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun `superseded noncooperative collection cannot publish into a new session`() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = ScriptedSource {
            calls++
            if (calls == 1) {
                // Deliberately bypass flow-builder cancellation checks to test the VM's
                // own session guard, rather than accidentally testing kotlinx.coroutines.
                object : Flow<NavigationLocation> {
                    override suspend fun collect(collector: FlowCollector<NavigationLocation>) {
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {
                            withContext(NonCancellable) {
                                release.await()
                                collector.emit(sampleFix().copy(speedKmh = 99.0))
                            }
                        }
                    }
                }
            } else flow { awaitCancellation() }
        }
        withNavigation(source) { vm ->
            try {
                vm.startNavigation(sampleRoute())
                runCurrent()
                vm.stopNavigation()
                runCurrent()
                advanceTimeBy(5_000)
                vm.startNavigation(sampleRoute())
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertNoInventedFix(vm)
                assertThat(calls).isEqualTo(2)
                advanceTimeBy(NavigationViewModel.GPS_STALE_MS - 1)
                runCurrent()
                assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isFalse()
                advanceTimeBy(1)
                runCurrent()
                assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isTrue()
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun `permission refusal on restart cancels the old cold watchdog without a new request`() = runTest(dispatcher) {
        var removed = 0
        val source = ScriptedSource { callbackFlow { awaitClose { removed++ } } }
        withNavigation(source) { vm ->
            vm.startNavigation(sampleRoute())
            runCurrent()
            advanceTimeBy(9_000)
            every {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            } returns PackageManager.PERMISSION_DENIED
            vm.startNavigation(sampleRoute())
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(NavigationState.LocationUnavailable)
            assertThat(source.attempts).containsExactly(1)
            assertThat(removed).isEqualTo(1)
            assertThat(source.events.none { it.first == NavigationGpsEvent.NO_FIX }).isTrue()
        }
    }

    @Test
    fun `fixes during rerouting stay healthy and a stale reroute result preserves loss`() = runTest(dispatcher) {
        val fixes = Channel<NavigationLocation>(Channel.UNLIMITED)
        val routeReady = CompletableDeferred<Unit>()
        val source = ScriptedSource { fixes.receiveAsFlow() }
        coEvery { routing.computeRoute(any()) } coAnswers {
            routeReady.await()
            sampleRoute()
        }
        withNavigation(source) { vm ->
            try {
                vm.startNavigation(sampleRoute())
                runCurrent()
                fixes.send(sampleFix().copy(latitude = 0.0, longitude = 0.0))
                runCurrent()
                assertThat(vm.state.value).isInstanceOf(NavigationState.Recalculating::class.java)
                advanceTimeBy(9_000)
                fixes.send(sampleFix())
                runCurrent()
                advanceTimeBy(3_000)
                runCurrent()
                assertThat((vm.state.value as NavigationState.Recalculating).previousState.gpsLost).isFalse()
                advanceTimeBy(9_000)
                runCurrent()
                assertThat((vm.state.value as NavigationState.Recalculating).previousState.gpsLost).isTrue()
                routeReady.complete(Unit)
                runCurrent()
                assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isTrue()
                fixes.send(sampleFix())
                runCurrent()
                assertThat((vm.state.value as NavigationState.Navigating).gpsLost).isFalse()
            } finally {
                routeReady.complete(Unit)
                fixes.close()
                runCurrent()
            }
        }
    }

    @Test
    fun `Stop rejects a noncooperative late reroute before it mutates a newer session`() = runTest(dispatcher) {
        val fixes = Channel<NavigationLocation>(Channel.UNLIMITED)
        val routeReady = CompletableDeferred<Unit>()
        val source = ScriptedSource { fixes.receiveAsFlow() }
        coEvery { routing.computeRoute(any()) } coAnswers {
            withContext(NonCancellable) {
                routeReady.await()
                // If the stale result replaces the private route even without a state
                // update, the next new-session fix would falsely announce arrival.
                sampleRoute().copy(geometry = listOf(sampleRoute().geometry.first()))
            }
        }
        withNavigation(source) { vm ->
            try {
                vm.startNavigation(sampleRoute())
                runCurrent()
                fixes.send(sampleFix().copy(latitude = 0.0, longitude = 0.0))
                runCurrent()
                assertThat(vm.state.value).isInstanceOf(NavigationState.Recalculating::class.java)
                vm.stopNavigation()
                runCurrent()
                vm.startNavigation(sampleRoute())
                runCurrent()
                routeReady.complete(Unit)
                runCurrent()
                assertNoInventedFix(vm)
                fixes.send(sampleFix())
                runCurrent()
                assertThat(vm.state.value).isInstanceOf(NavigationState.Navigating::class.java)
                assertThat((vm.state.value as NavigationState.Navigating).awaitingGpsFix).isFalse()
                verify(exactly = 0) { tts.speakArrival() }
                verify(exactly = 1) { tts.speakText(any()) } // Only the pre-Stop rerouting notice.
            } finally {
                routeReady.complete(Unit)
                fixes.close()
                runCurrent()
            }
        }
    }

    private suspend fun TestScope.withNavigation(
        source: NavigationLocationSource,
        block: suspend TestScope.(NavigationViewModel) -> Unit,
    ) {
        val vm = NavigationViewModel(context, source, tts, routing, hazards)
        try {
            block(vm)
        } finally {
            vm.stopNavigation()
            runCurrent() // bounded cleanup BEFORE runTest can drain an endless watchdog.
        }
    }

    private fun assertNoInventedFix(vm: NavigationViewModel) {
        val s = vm.state.value as NavigationState.Navigating
        assertThat(s.awaitingGpsFix).isTrue()
        assertThat(s.currentSpeedKmh).isEqualTo(0.0)
        assertThat(s.remainingDistanceMeters).isEqualTo(2_500.0)
        assertThat(s.remainingDurationSeconds).isEqualTo(300.0)
        assertThat(s.nextManeuverDistanceMeters).isNull()
    }

    private fun sampleRoute() = RouteResult(
        distanceMeters = 2_500,
        durationSeconds = 300,
        geometry = listOf(
            GeoPoint(longitude = 138.600, latitude = -34.900),
            GeoPoint(longitude = 138.610, latitude = -34.910),
            GeoPoint(longitude = 138.620, latitude = -34.920),
        ),
        maneuvers = emptyList(),
    )

    private fun sampleFix() = NavigationLocation(
        latitude = -34.900,
        longitude = 138.600,
        speedKmh = 36.0,
        bearing = 0f,
        accuracyMeters = 5f,
        timestamp = 1_000L,
    )

    private class ScriptedSource(private val updates: (Int) -> Flow<NavigationLocation>) :
        DiagnosedNavigationLocationSource {
        val attempts = mutableListOf<Int>()
        val events = mutableListOf<Pair<NavigationGpsEvent, Int>>()

        override fun locationUpdates(intervalMs: Long) = locationUpdates(intervalMs, attempt = 1)

        override fun locationUpdates(intervalMs: Long, attempt: Int): Flow<NavigationLocation> {
            attempts += attempt
            return updates(attempt)
        }

        override fun recordGpsEvent(event: NavigationGpsEvent, attempt: Int) {
            events += event to attempt
        }
    }
}
