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
    private lateinit var viewModel: NavigationViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        locationSource = mockk(relaxed = true)
        tts = mockk(relaxed = true)
        routingEngine = mockk(relaxed = true)
        viewModel = NavigationViewModel(context, locationSource, tts, routingEngine)
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
}
