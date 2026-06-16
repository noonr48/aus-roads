package au.com.ausroads.routing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.CostingProfile
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.valhalla.ValhallaRoutingEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end proof that the Valhalla engine computes a real route over the
 * generated South Australia tiles.
 *
 * Reads the `tile_extract` tar from /data/local/tmp (push it first with
 * `adb push valhalla_tiles.tar /data/local/tmp/`). When the tar is not staged the
 * test is skipped via [assumeTrue] so it never breaks a CI run that lacks the
 * ~100 MB tiles.
 */
@RunWith(AndroidJUnit4::class)
class RoutingEngineInstrumentedTest {

    @Test
    fun computesAdelaideRoute_overGeneratedTiles() {
        val tar = File(TAR_PATH)
        assumeTrue("Routing tiles not staged at $TAR_PATH; skipping", tar.isFile && tar.length() > 0)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ValhallaRoutingEngine(context)
        engine.initialize(tar.absolutePath)
        assertThat(engine.isReady()).isTrue()

        val result = runBlocking {
            engine.computeRoute(
                RouteRequest(
                    origin = GeoPoint(latitude = -34.9285, longitude = 138.6007),      // Adelaide CBD
                    destination = GeoPoint(latitude = -34.9066, longitude = 138.5950), // North Adelaide
                    costingProfile = CostingProfile.AUTO,
                ),
            )
        }

        assertThat(result.distanceMeters).isGreaterThan(0)
        assertThat(result.geometry).isNotEmpty()
        assertThat(result.maneuvers).isNotEmpty()
    }

    private companion object {
        private const val TAR_PATH = "/data/local/tmp/valhalla_tiles.tar"
    }
}
