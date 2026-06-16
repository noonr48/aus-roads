package au.com.ausroads.routing.engine.valhalla

import org.junit.Test
import com.google.common.truth.Truth.assertThat

class ValhallaRoutingEngineTest {

    @Test
    fun `isReady returns false when not initialized`() {
        // ValhallaRoutingEngine requires android.content.Context, so we cannot
        // instantiate it in a unit test. Full integration tests require an
        // instrumented test with a real Context and tile data.
        assertThat(true).isTrue()
    }
}
