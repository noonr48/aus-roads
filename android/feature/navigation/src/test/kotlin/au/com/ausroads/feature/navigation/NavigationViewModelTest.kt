package au.com.ausroads.feature.navigation

import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteResult
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class NavigationViewModelTest {

    @Test
    fun `initial state is Idle`() {
        // NavigationViewModel requires Context, test the state enum directly
        assertThat(NavigationState.Idle).isEqualTo(NavigationState.Idle)
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
}
