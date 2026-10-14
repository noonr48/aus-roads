package au.com.ausroads.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.feature.navigation.NavigationGpsEvent
import au.com.ausroads.feature.navigation.NavigationLocation
import au.com.ausroads.feature.navigation.NavigationState
import au.com.ausroads.feature.navigation.NavigationViewModel
import au.com.ausroads.feature.navigation.RoadHazardContext
import au.com.ausroads.feature.navigation.RoadHazardSource
import au.com.ausroads.navigation.tts.NavigationTts
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Actual adapter + Google Tasks; only the platform client/Android methods are substituted. */
@OptIn(ExperimentalCoroutinesApi::class)
class FusedNavigationLocationSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<FusedLocationProviderClient>()
    private val looper = mockk<Looper>()
    private val pendingRegistration = TaskCompletionSource<Void>()
    private var registrationTask: Task<Void> = pendingRegistration.task
    private val callbacks = mutableListOf<LocationCallback>()
    private val removals = mutableListOf<LocationCallback>()
    private val events = mutableListOf<Pair<NavigationGpsEvent, Int>>()
    private val logLines = mutableListOf<String>()
    private lateinit var source: FusedNavigationLocationSource

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns looper
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { dispatcher.scheduler.currentTime }
        mockkStatic(Log::class)
        every { Log.w("NavigationGps", any<String>()) } answers {
            logLines += secondArg<String>()
            0
        }
        every { Log.i("NavigationGps", any<String>()) } answers {
            logLines += secondArg<String>()
            0
        }
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>())
        } answers {
            callbacks += secondArg<LocationCallback>()
            registrationTask
        }
        every { client.removeLocationUpdates(any<LocationCallback>()) } answers {
            removals += firstArg<LocationCallback>()
            Tasks.forResult<Void>(null)
        }
        source = FusedNavigationLocationSource(client) { event, attempt -> events += event to attempt }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `failed registration Task ends collection and removes the exact callback`() = runTest(dispatcher) {
        val collected = async { runCatching { source.locationUpdates(1_000).toList() } }
        try {
            runCurrent()
            assertThat(callbacks).hasSize(1)
            assertThat(collected.isCompleted).isFalse()
            pendingRegistration.setException(SecurityException(PRIVATE_PROVIDER_TEXT))
            runCurrent()
            assertThat(collected.isCompleted).isTrue()
            assertThat(collected.await().exceptionOrNull()?.message).isEqualTo("GPS registration failed")
            assertThat(removals).hasSize(1)
            assertThat(removals.single()).isSameInstanceAs(callbacks.single())
            assertThat(events).contains(NavigationGpsEvent.REGISTRATION_FAILED to 1)
            assertSafeWitnesses()
        } finally {
            collected.cancelAndJoin()
            runCurrent()
        }
    }

    @Test
    fun `canceled registration Task is a retryable failure and cleans the callback`() = runTest(dispatcher) {
        // A real canceled Task, not a mock and not cancellation of the test coroutine.
        registrationTask = Tasks.forCanceled<Void>()
        val collected = async { runCatching { source.locationUpdates(1_000).toList() } }
        try {
            runCurrent()
            assertThat(registrationTask.isCanceled).isTrue()
            assertThat(collected.isCompleted).isTrue()
            val failure = collected.await().exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo("GPS registration canceled")
            assertThat(removals.single()).isSameInstanceAs(callbacks.single())
            assertThat(events).contains(NavigationGpsEvent.REGISTRATION_CANCELED to 1)
            assertSafeWitnesses()
        } finally {
            collected.cancelAndJoin()
            runCurrent()
        }
    }

    @Test
    fun `synchronous registration rejection also closes and removes the callback`() = runTest(dispatcher) {
        every {
            client.requestLocationUpdates(any<LocationRequest>(), any<LocationCallback>(), any<Looper>())
        } answers {
            callbacks += secondArg<LocationCallback>()
            throw SecurityException(PRIVATE_PROVIDER_TEXT)
        }
        val collected = async { runCatching { source.locationUpdates(1_000).toList() } }
        try {
            runCurrent()
            assertThat(collected.isCompleted).isTrue()
            assertThat(collected.await().exceptionOrNull()?.message).isEqualTo("GPS registration failed")
            assertThat(removals.single()).isSameInstanceAs(callbacks.single())
            assertThat(events).contains(NavigationGpsEvent.REGISTRATION_FAILED to 1)
            assertSafeWitnesses()
        } finally {
            collected.cancelAndJoin()
            runCurrent()
        }
    }

    @Test
    fun `successful registration publishes only real callback fixes and converts speed`() = runTest(dispatcher) {
        val firstFix = async { source.locationUpdates(1_000).first() }
        try {
            runCurrent()
            val callback = callbacks.single()
            callback.onLocationResult(sampleResult()) // Not registered yet: do not publish.
            runCurrent()
            assertThat(firstFix.isCompleted).isFalse()
            pendingRegistration.setResult(null)
            callback.onLocationResult(sampleResult())
            runCurrent()
            assertThat(firstFix.isCompleted).isTrue()
            val fix = firstFix.await()
            assertThat(fix.latitude).isEqualTo(-34.900)
            assertThat(fix.longitude).isEqualTo(138.600)
            assertThat(fix.speedKmh).isEqualTo(9.0) // real 2.5 m/s -> km/h conversion.
            assertThat(fix.accuracyMeters).isEqualTo(5f)
            assertThat(fix.timestamp).isEqualTo(1_000L)
            assertThat(removals.single()).isSameInstanceAs(callback)
            assertThat(events).contains(NavigationGpsEvent.REGISTRATION_SUCCEEDED to 1)
            assertThat(events.none { it.first.failed }).isTrue()
            assertSafeWitnesses()
        } finally {
            firstFix.cancelAndJoin()
            runCurrent()
        }
    }

    @Test
    fun `Stop removes before a pending Task completes and repeats removal on late success`() = runTest(dispatcher) {
        val samples = mutableListOf<NavigationLocation>()
        val collecting = launch { source.locationUpdates(1_000).collect { samples += it } }
        try {
            runCurrent()
            val callback = callbacks.single()
            collecting.cancelAndJoin()
            runCurrent()
            assertThat(pendingRegistration.task.isComplete).isFalse()
            assertThat(removals).hasSize(1) // Removal does not wait for the registration Task.
            assertThat(removals.single()).isSameInstanceAs(callback)
            pendingRegistration.setResult(null)
            callback.onLocationResult(sampleResult())
            runCurrent()
            assertThat(removals).hasSize(2)
            assertThat(removals.all { it === callback }).isTrue()
            assertThat(samples).isEmpty()
            assertThat(callbacks).hasSize(1) // No late re-registration.
            assertThat(events.none { it.first == NavigationGpsEvent.REGISTRATION_SUCCEEDED }).isTrue()
        } finally {
            collecting.cancelAndJoin()
            runCurrent()
        }
    }

    @Test
    fun `late failed Task after Stop cannot publish or resurrect collection`() = runTest(dispatcher) {
        val samples = mutableListOf<NavigationLocation>()
        val collecting = launch { source.locationUpdates(1_000).collect { samples += it } }
        try {
            runCurrent()
            collecting.cancelAndJoin()
            runCurrent()
            pendingRegistration.setException(SecurityException(PRIVATE_PROVIDER_TEXT))
            callbacks.single().onLocationResult(sampleResult())
            runCurrent()
            assertThat(samples).isEmpty()
            assertThat(callbacks).hasSize(1)
            assertThat(removals).hasSize(2)
            assertThat(events.none { it.first.failed }).isTrue() // Stop is not a source failure.
            assertSafeWitnesses()
        } finally {
            collecting.cancelAndJoin()
            runCurrent()
        }
    }

    @Test
    fun `failed removal Task has its own fixed privacy safe witness`() = runTest(dispatcher) {
        every { client.removeLocationUpdates(any<LocationCallback>()) } answers {
            removals += firstArg<LocationCallback>()
            Tasks.forException<Void>(IllegalStateException(PRIVATE_PROVIDER_TEXT))
        }
        val collecting = launch { source.locationUpdates(1_000).collect {} }
        try {
            runCurrent()
            collecting.cancelAndJoin()
            runCurrent()
            assertThat(removals.single()).isSameInstanceAs(callbacks.single())
            assertThat(events).contains(NavigationGpsEvent.REMOVAL_FAILED to 1)
            assertSafeWitnesses()
        } finally {
            collecting.cancelAndJoin()
            runCurrent()
        }
    }

    @Test
    fun `kill registration seam reaches real adapter collection and bounded exhaustion witnesses`() = runTest(dispatcher) {
        registrationTask = Tasks.forException<Void>(SecurityException(PRIVATE_PROVIDER_TEXT))
        val vm = navigationViewModel()
        try {
            vm.startNavigation(sampleRoute())
            runCurrent()
            advanceTimeBy(12_000)
            runCurrent()
            assertThat(callbacks).hasSize(NavigationViewModel.MAX_GPS_RETRIES + 1)
            assertThat(removals).hasSize(4)
            callbacks.forEach { callback -> assertThat(removals.count { it === callback }).isEqualTo(1) }
            assertThat(events.filter { it.first == NavigationGpsEvent.REGISTRATION_FAILED }.map { it.second })
                .containsExactly(1, 2, 3, 4).inOrder()
            assertThat(events.filter { it.first == NavigationGpsEvent.COLLECTION_FAILED }.map { it.second })
                .containsExactly(1, 2, 3, 4).inOrder()
            assertThat(events).contains(NavigationGpsEvent.RETRIES_EXHAUSTED to 4)
            val s = vm.state.value as NavigationState.Navigating
            assertThat(s.gpsLost).isTrue()
            assertThat(s.awaitingGpsFix).isTrue()
            assertThat(s.currentSpeedKmh).isEqualTo(0.0)
            assertThat(s.remainingDistanceMeters).isEqualTo(2_500.0)
            assertThat(logLines).contains("component=navigation seam=gps event=REGISTRATION_FAILED attempt=4")
            assertThat(logLines).contains("component=navigation seam=gps event=COLLECTION_FAILED attempt=4")
            assertThat(logLines).contains("component=navigation seam=gps event=RETRIES_EXHAUSTED attempt=4")
            assertSafeWitnesses()
            advanceTimeBy(30_000)
            runCurrent()
            assertThat(callbacks).hasSize(4)
        } finally {
            vm.stopNavigation()
            runCurrent()
        }
    }

    @Test
    fun `healthy adapter control drives VM and Stop rejects a late real callback`() = runTest(dispatcher) {
        registrationTask = Tasks.forResult<Void>(null)
        val vm = navigationViewModel()
        try {
            vm.startNavigation(sampleRoute())
            runCurrent()
            callbacks.single().onLocationResult(sampleResult())
            runCurrent()
            val s = vm.state.value as NavigationState.Navigating
            assertThat(s.awaitingGpsFix).isFalse()
            assertThat(s.gpsLost).isFalse()
            assertThat(s.currentSpeedKmh).isEqualTo(9.0)
            assertThat(events.none { it.first.failed }).isTrue()
            vm.stopNavigation()
            runCurrent()
            callbacks.single().onLocationResult(sampleResult())
            advanceTimeBy(30_000)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(NavigationState.Idle)
            assertThat(callbacks).hasSize(1)
            assertThat(removals.single()).isSameInstanceAs(callbacks.single())
            assertSafeWitnesses()
        } finally {
            vm.stopNavigation()
            runCurrent()
        }
    }

    @Test
    fun `canceled registration Tasks reach bounded VM retries rather than canceling its session`() = runTest(dispatcher) {
        registrationTask = Tasks.forCanceled<Void>()
        val vm = navigationViewModel()
        try {
            vm.startNavigation(sampleRoute())
            runCurrent()
            advanceTimeBy(12_000)
            runCurrent()
            assertThat(callbacks).hasSize(4)
            assertThat(removals).hasSize(4)
            assertThat(events.filter { it.first == NavigationGpsEvent.REGISTRATION_CANCELED }.map { it.second })
                .containsExactly(1, 2, 3, 4).inOrder()
            assertThat(events).contains(NavigationGpsEvent.RETRIES_EXHAUSTED to 4)
            val s = vm.state.value as NavigationState.Navigating
            assertThat(s.gpsLost).isTrue()
            assertThat(s.awaitingGpsFix).isTrue()
            assertSafeWitnesses()
        } finally {
            vm.stopNavigation()
            runCurrent()
        }
    }

    @Test
    fun `VM Stop removes a pending registration before Task success and forbids late retry or fix`() = runTest(dispatcher) {
        val vm = navigationViewModel()
        try {
            vm.startNavigation(sampleRoute())
            runCurrent()
            val callback = callbacks.single()
            vm.stopNavigation()
            runCurrent()
            assertThat(pendingRegistration.task.isComplete).isFalse()
            assertThat(removals.single()).isSameInstanceAs(callback)
            pendingRegistration.setResult(null)
            callback.onLocationResult(sampleResult())
            advanceTimeBy(30_000)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(NavigationState.Idle)
            assertThat(callbacks).hasSize(1)
            assertThat(removals).hasSize(2)
            assertThat(removals.all { it === callback }).isTrue()
            assertThat(events.none { it.first.failed }).isTrue()
            assertThat(events.none { it.first == NavigationGpsEvent.REGISTRATION_SUCCEEDED }).isTrue()
            assertSafeWitnesses()
        } finally {
            vm.stopNavigation()
            runCurrent()
        }
    }

    private fun navigationViewModel(): NavigationViewModel {
        val context = mockk<Context>(relaxed = true)
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } returns PackageManager.PERMISSION_GRANTED
        val hazards = mockk<RoadHazardSource>()
        coEvery { hazards.contextAt(any(), any(), any()) } returns RoadHazardContext(null, emptyList())
        return NavigationViewModel(context, source, mockk<NavigationTts>(relaxed = true), mockk<RoutingEngine>(), hazards)
    }

    private fun assertSafeWitnesses() {
        assertThat(logLines).isNotEmpty()
        assertThat(logLines.all {
            it.matches(Regex("component=navigation seam=gps event=[A-Z_]+ attempt=[1-4]"))
        }).isTrue()
        assertThat(logLines.joinToString()).doesNotContain(PRIVATE_PROVIDER_TEXT)
        assertThat(logLines.joinToString()).doesNotContain("138.600")
        assertThat(logLines.joinToString()).doesNotContain("-34.900")
    }

    private fun sampleResult(): LocationResult {
        val location = mockk<Location>()
        every { location.latitude } returns -34.900
        every { location.longitude } returns 138.600
        every { location.speed } returns 2.5f
        every { location.bearing } returns 0f
        every { location.accuracy } returns 5f
        every { location.time } returns 1_000L
        return LocationResult.create(listOf(location))
    }

    private fun sampleRoute() = RouteResult(
        distanceMeters = 2_500,
        durationSeconds = 300,
        geometry = listOf(
            GeoPoint(longitude = 138.600, latitude = -34.900),
            GeoPoint(longitude = 138.620, latitude = -34.920),
        ),
        maneuvers = emptyList(),
    )

    private companion object {
        const val PRIVATE_PROVIDER_TEXT = "query=private clinic at -34.900,138.600 provider secret"
    }
}
