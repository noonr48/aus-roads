package au.com.ausroads.routing.engine.valhalla

import au.com.ausroads.routing.engine.RouteOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoCostingOptionsTest {

    @Test
    fun `default RouteOptions leaves all avoidance factors unset`() {
        val options = ValhallaRoutingEngine.buildAutoCostingOptions(RouteOptions())
        assertThat(options.useTolls).isNull()
        assertThat(options.useFerry).isNull()
        assertThat(options.useTracks).isNull()
    }

    @Test
    fun `avoidTolls sets useTolls to 0`() {
        val options = ValhallaRoutingEngine.buildAutoCostingOptions(
            RouteOptions(avoidTolls = true),
        )
        assertThat(options.useTolls).isEqualTo(0.0)
        // unrelated factors stay unset
        assertThat(options.useFerry).isNull()
        assertThat(options.useTracks).isNull()
    }

    @Test
    fun `avoidFerries sets useFerry to 0`() {
        val options = ValhallaRoutingEngine.buildAutoCostingOptions(
            RouteOptions(avoidFerries = true),
        )
        assertThat(options.useFerry).isEqualTo(0.0)
        assertThat(options.useTolls).isNull()
        assertThat(options.useTracks).isNull()
    }

    @Test
    fun `avoidUnsealed sets useTracks to 0`() {
        val options = ValhallaRoutingEngine.buildAutoCostingOptions(
            RouteOptions(avoidUnsealed = true),
        )
        assertThat(options.useTracks).isEqualTo(0.0)
        assertThat(options.useTolls).isNull()
        assertThat(options.useFerry).isNull()
    }

    @Test
    fun `all avoidances set all three factors to 0`() {
        val options = ValhallaRoutingEngine.buildAutoCostingOptions(
            RouteOptions(avoidTolls = true, avoidUnsealed = true, avoidFerries = true),
        )
        assertThat(options.useTolls).isEqualTo(0.0)
        assertThat(options.useFerry).isEqualTo(0.0)
        assertThat(options.useTracks).isEqualTo(0.0)
    }
}
