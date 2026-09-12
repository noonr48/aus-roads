package au.com.ausroads.feature.navigation

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.navigation.tts.NavigationTts
import au.com.ausroads.routing.engine.RoutingEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Behavioral guards for navigation ENTRY semantics. Regression coverage for the
 * privacy contract (docs/notes/privacy.md v0.7): without ACCESS_FINE_LOCATION,
 * navigation mode is UNAVAILABLE — the VM must publish LocationUnavailable and
 * must never fabricate progress toward arrival (the retired simulator walked
 * geometry on a timer instead, manufacturing drives on gms-free devices).
 */
class NavigationViewModelBehaviorTest {

    // NavigationViewModel builds viewModelScope on Dispatchers.Main — supply a
    // test Main (house pattern: MapPackManagerTest / MapPackViewModelTest).
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var context: Context
    private lateinit var locationSource: NavigationLocationSource
    private lateinit var tts: NavigationTts
    private lateinit var routingEngine: RoutingEngine
    private lateinit var roadHazards: FakeRoadHazardSource
    private lateinit var viewModel: NavigationViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        locationSource = mockk(relaxed = true)
        tts = mockk(relaxed = true)
        routingEngine = mockk(relaxed = true)
        // SystemClock.elapsedRealtime (the staleness watchdog's clock) is an
        // android framework method that throws on the JVM mockable jar — pin it
        // to a monotonic constant (house static-mock pattern, see grantPermission).
        mockkStatic(android.os.SystemClock::class)
        every { android.os.SystemClock.elapsedRealtime() } returns 1_000L
        roadHazards = FakeRoadHazardSource()
        viewModel = NavigationViewModel(context, locationSource, tts, routingEngine, roadHazards)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun sampleRoute(): RouteResult {
        val maneuver = Maneuver(
            instruction = "Head south",
            lengthMeters = 500,
            durationSeconds = 60,
            beginShapeIndex = 0,
            streetName = "King William St",
            maneuverType = "depart",
        )
        val geometry = listOf(
            GeoPoint(longitude = 138.600, latitude = -34.900),
            GeoPoint(longitude = 138.610, latitude = -34.910),
            GeoPoint(longitude = 138.620, latitude = -34.920),
        )
        return RouteResult(
            distanceMeters = 2500,
            durationSeconds = 300,
            geometry = geometry,
            maneuvers = listOf(maneuver),
        )
    }

    private fun grantPermission(granted: Boolean) {
        // ContextCompat.checkSelfPermission executes real androidx internals
        // (TextUtils.equals at ContextCompat.java:547) that throw on the
        // mockable android jar, so the static itself is intercepted (house
        // pattern: mockkStatic(WorkManager) in MapPackManagerTest). The VM's
        // reaction to the permission answer is what we exercise.
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any<String>())
        } returns if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    @Test
    fun `startNavigation without location permission publishes LocationUnavailable`() {
        grantPermission(granted = false)

        viewModel.startNavigation(sampleRoute())

        assertThat(viewModel.state.value).isEqualTo(NavigationState.LocationUnavailable)
    }

    @Test
    fun `refused entry never reaches Navigating or touches TTS`() {
        grantPermission(granted = false)

        viewModel.startNavigation(sampleRoute())

        val state = viewModel.state.value
        assertThat(state !is NavigationState.Navigating).isTrue()
        verify(exactly = 0) { tts.initialize() }
        verify(exactly = 0) { locationSource.locationUpdates(any()) }
    }

    @Test
    fun `granted permission still starts genuine GPS navigation session`() {
        grantPermission(granted = true)
        // No samples needed for entry assertions; ending the flow immediately
        // keeps runTest deterministic (no wedged collectors).
        every { locationSource.locationUpdates(any()) } returns emptyFlow()

        viewModel.startNavigation(sampleRoute())

        val state = viewModel.state.value
        assertThat(state).isInstanceOf(NavigationState.Navigating::class.java)
        verify(exactly = 1) { tts.initialize() }
    }

    @Test
    fun `stopNavigation clears refused session back to Idle`() {
        grantPermission(granted = false)
        viewModel.startNavigation(sampleRoute())

        viewModel.stopNavigation()

        assertThat(viewModel.state.value).isEqualTo(NavigationState.Idle)
    }

    @Test
    fun `dismissLocationUnavailable returns to Idle`() {
        grantPermission(granted = false)
        viewModel.startNavigation(sampleRoute())

        viewModel.dismissLocationUnavailable()

        assertThat(viewModel.state.value).isEqualTo(NavigationState.Idle)
    }

    @Test
    fun `posted limit flows into Navigating state with hysteresis overspeed`() {
        grantPermission(granted = true)
        every { locationSource.locationUpdates(any()) } returns emptyFlow()
        viewModel.startNavigation(sampleRoute())
        roadHazards.contextToReturn = RoadHazardContext(
            speedLimitKmh = 60,
            cameras = emptyList(),
        )

        val onRoute = GeoPoint(longitude = 138.6005, latitude = -34.9005)

        viewModel.updatePosition(onRoute, speedKmh = 50.0, bearingDegrees = 0f)
        val atLimit = viewModel.state.value as NavigationState.Navigating
        assertThat(atLimit.speedLimitKmh).isEqualTo(60)
        assertThat(atLimit.isOverspeeding).isFalse()
        assertThat(atLimit.awaitingGpsFix).isFalse()

        viewModel.updatePosition(onRoute, speedKmh = 70.0, bearingDegrees = 0f)
        assertThat((viewModel.state.value as NavigationState.Navigating).isOverspeeding).isTrue()

        // Inside the 5 km/h buffer band the OVER state latches (jitter guard).
        viewModel.updatePosition(onRoute, speedKmh = 61.0, bearingDegrees = 0f)
        assertThat((viewModel.state.value as NavigationState.Navigating).isOverspeeding).isTrue()

        viewModel.updatePosition(onRoute, speedKmh = 58.0, bearingDegrees = 0f)
        assertThat((viewModel.state.value as NavigationState.Navigating).isOverspeeding).isFalse()
    }

    @Test
    fun `camera within warning radius announces once and publishes warning`() {
        grantPermission(granted = true)
        every { locationSource.locationUpdates(any()) } returns emptyFlow()
        viewModel.startNavigation(sampleRoute())
        val camera = RoadHazardCamera(
            id = "cam-1",
            // ~200 m north of the on-route position, dead ahead of bearing 0.
            latitude = -34.8987,
            longitude = 138.6005,
            maxspeedKmh = 60,
            wayName = "Main North Rd",
            distanceMeters = 200.0,
        )
        roadHazards.contextToReturn = RoadHazardContext(
            speedLimitKmh = 60,
            cameras = listOf(camera),
        )

        val onRoute = GeoPoint(longitude = 138.6005, latitude = -34.9005)
        viewModel.updatePosition(onRoute, speedKmh = 50.0, bearingDegrees = 0f)

        val state = viewModel.state.value as NavigationState.Navigating
        assertThat(state.cameraWarning).isNotNull()
        assertThat(state.cameraWarning?.id).isEqualTo("cam-1")
        assertThat(state.cameraWarning?.distanceMeters).isNotNull()
        verify(exactly = 1) { tts.speakText(any()) }

        // A subsequent fix at the same place must not re-announce.
        viewModel.updatePosition(onRoute, speedKmh = 50.0, bearingDegrees = 0f)
        verify(exactly = 1) { tts.speakText(any()) }
        assertThat(
            (viewModel.state.value as NavigationState.Navigating).cameraWarning?.id,
        ).isEqualTo("cam-1")
    }

    @Test
    fun `passed camera does not resurrect the warning on later fixes`() {
        grantPermission(granted = true)
        every { locationSource.locationUpdates(any()) } returns emptyFlow()
        viewModel.startNavigation(sampleRoute())
        val camera = RoadHazardCamera(
            id = "cam-pass",
            latitude = -34.8987,
            longitude = 138.6005,
            maxspeedKmh = 60,
            wayName = null,
            distanceMeters = 200.0,
        )
        roadHazards.contextToReturn = RoadHazardContext(
            speedLimitKmh = 60,
            cameras = listOf(camera),
        )

        // Enter the camera zone: warning publishes + one announcement.
        val onRoute = GeoPoint(longitude = 138.6005, latitude = -34.9005)
        viewModel.updatePosition(onRoute, speedKmh = 50.0, bearingDegrees = 0f)
        assertThat(
            (viewModel.state.value as NavigationState.Navigating).cameraWarning?.id,
        ).isEqualTo("cam-pass")

        // Drive well past the camera (beyond radius * exit slack) while staying
        // near a route vertex so the off-route recalc path does not fire (the
        // vertex-nearest engine flags mid-segment positions >100 m from any
        // vertex). Vertex 2 is ~1.2 km from the camera — beyond the 600 m exit.
        val farPast = GeoPoint(longitude = 138.6098, latitude = -34.9098)
        roadHazards.contextToReturn = RoadHazardContext(
            speedLimitKmh = 60,
            cameras = emptyList(),
        )
        viewModel.updatePosition(farPast, speedKmh = 50.0, bearingDegrees = 0f)
        viewModel.updatePosition(farPast, speedKmh = 50.0, bearingDegrees = 0f)
        viewModel.updatePosition(farPast, speedKmh = 50.0, bearingDegrees = 0f)

        val state = viewModel.state.value as NavigationState.Navigating
        assertThat(state.cameraWarning).isNull()
        // Still exactly the single entry announcement.
        verify(exactly = 1) { tts.speakText(any()) }
    }

    @Test
    fun `camera still returned by the pack after passing does not resurrect`() {
        grantPermission(granted = true)
        every { locationSource.locationUpdates(any()) } returns emptyFlow()
        viewModel.startNavigation(sampleRoute())
        val camera = RoadHazardCamera(
            id = "cam-near",
            latitude = -34.8987,
            longitude = 138.6005,
            maxspeedKmh = 60,
            wayName = null,
            distanceMeters = 200.0,
        )
        // The pack keeps returning the camera for every refresh — only the
        // ProximityEngine isInside gate (not the retainAll prune) can stop the
        // passed camera from re-entering the warning.
        roadHazards.contextToReturn = RoadHazardContext(
            speedLimitKmh = 60,
            cameras = listOf(camera),
        )

        val onRoute = GeoPoint(longitude = 138.6005, latitude = -34.9005)
        viewModel.updatePosition(onRoute, speedKmh = 50.0, bearingDegrees = 0f)
        assertThat(
            (viewModel.state.value as NavigationState.Navigating).cameraWarning?.id,
        ).isEqualTo("cam-near")

        // Far past the camera but still inside the 2 km query window: the fake
        // returns it again on refresh, membership has exited (>600 m), so the
        // isInside gate must block adoption on every subsequent fix.
        val farPast = GeoPoint(longitude = 138.6098, latitude = -34.9098)
        viewModel.updatePosition(farPast, speedKmh = 50.0, bearingDegrees = 0f)
        viewModel.updatePosition(farPast, speedKmh = 50.0, bearingDegrees = 0f)
        viewModel.updatePosition(farPast, speedKmh = 50.0, bearingDegrees = 0f)

        val state = viewModel.state.value as NavigationState.Navigating
        assertThat(state.cameraWarning).isNull()
        verify(exactly = 1) { tts.speakText(any()) }
    }

    /** Pack-free stand-in for the road-hazard port. */
    private class FakeRoadHazardSource : RoadHazardSource {
        var contextToReturn = RoadHazardContext(
            speedLimitKmh = null,
            cameras = emptyList(),
        )

        override suspend fun contextAt(
            latitude: Double,
            longitude: Double,
            radiusMeters: Double,
        ) = contextToReturn
    }
}
