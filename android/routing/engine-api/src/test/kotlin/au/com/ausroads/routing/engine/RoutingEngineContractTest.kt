/*
 * RoutingEngine is an interface; the v0.1 module ships API only. These tests pin the
 * data classes' defaults, enum coverage, and equality so a future contributor can
 * refactor without silently breaking the contract with v0.4's Valhalla backend.
 */
package au.com.ausroads.routing.engine

import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.core.model.RoutingEffect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RoutingEngineContractTest {

    @Test
    fun `RouteRequest defaults costingProfile to AUTO`() {
        val request = RouteRequest(
            origin = GeoPoint(138.6, -34.9),
            destination = GeoPoint(138.7, -34.8),
        )

        assertThat(request.costingProfile).isEqualTo(CostingProfile.AUTO)
    }

    @Test
    fun `RouteRequest defaults via, avoidPolygons, and penalties to empty`() {
        val request = RouteRequest(
            origin = GeoPoint(138.6, -34.9),
            destination = GeoPoint(138.7, -34.8),
        )

        assertThat(request.via).isEmpty()
        assertThat(request.avoidPolygons).isEmpty()
        assertThat(request.penalties).isEmpty()
        assertThat(request.departAt).isNull()
    }

    @Test
    fun `RouteRequest equality holds for identical fields`() {
        val a = RouteRequest(
            origin = GeoPoint(138.6, -34.9),
            destination = GeoPoint(138.7, -34.8),
        )
        val b = RouteRequest(
            origin = GeoPoint(138.6, -34.9),
            destination = GeoPoint(138.7, -34.8),
        )

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `RouteRequest inequality when costingProfile differs`() {
        val a = RouteRequest(
            origin = GeoPoint(138.6, -34.9),
            destination = GeoPoint(138.7, -34.8),
            costingProfile = CostingProfile.AUTO,
        )
        val b = a.copy(costingProfile = CostingProfile.BICYCLE)

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `CostingProfile enum has at least the expected values`() {
        val values = CostingProfile.values().toSet()

        assertThat(values).containsExactly(
            CostingProfile.AUTO,
            CostingProfile.MOTORCYCLE,
            CostingProfile.TRUCK,
            CostingProfile.BICYCLE,
            CostingProfile.PEDESTRIAN,
        )
    }

    @Test
    fun `CostingProfile values are distinct`() {
        val values = CostingProfile.values()

        assertThat(values.toSet()).hasSize(values.size)
    }

    @Test
    fun `RouteResult equality on distance and duration`() {
        val a = RouteResult(
            distanceMeters = 12_345,
            durationSeconds = 678,
            geometry = listOf(GeoPoint(138.6, -34.9), GeoPoint(138.7, -34.8)),
            maneuvers = emptyList(),
        )
        val b = RouteResult(
            distanceMeters = 12_345,
            durationSeconds = 678,
            geometry = listOf(GeoPoint(138.6, -34.9), GeoPoint(138.7, -34.8)),
            maneuvers = emptyList(),
        )

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `RouteResult inequality when distance differs`() {
        val a = RouteResult(
            distanceMeters = 12_345,
            durationSeconds = 678,
            geometry = emptyList(),
            maneuvers = emptyList(),
        )
        val b = a.copy(distanceMeters = 12_346)

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `RouteResult inequality when duration differs`() {
        val a = RouteResult(
            distanceMeters = 12_345,
            durationSeconds = 678,
            geometry = emptyList(),
            maneuvers = emptyList(),
        )
        val b = a.copy(durationSeconds = 679)

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `RouteResult defaults warnings to empty`() {
        val result = RouteResult(
            distanceMeters = 1,
            durationSeconds = 1,
            geometry = emptyList(),
            maneuvers = emptyList(),
        )

        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `RoutePenalty carries its geometry and effect`() {
        val penalty = RoutePenalty(
            geometry = listOf(GeoPoint(138.6, -34.9), GeoPoint(138.7, -34.8)),
            effect = RoutingEffect.Penalty(15),
        )

        assertThat(penalty.geometry).hasSize(2)
        assertThat(penalty.effect).isEqualTo(RoutingEffect.Penalty(15))
    }

    @Test
    fun `Maneuver equality holds for identical fields`() {
        val a = Maneuver(
            instruction = "Turn right onto Main St",
            lengthMeters = 250,
            durationSeconds = 30,
            beginShapeIndex = 5,
            streetName = "Main St",
            maneuverType = "turn",
        )
        val b = a.copy()

        assertThat(a).isEqualTo(b)
    }
}
