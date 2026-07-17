package au.com.ausroads.ui.map

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.MainActivity
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

@RunWith(AndroidJUnit4::class)
class MapColdStartInstrumentedTest {
    @Test
    fun coldLaunchPublishesTheStyledInteractiveMap() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val styleReady = CountDownLatch(1)
            val callbackFailure = AtomicReference<Throwable?>()

            onView(isAssignableFrom(MapView::class.java)).check { view, noViewFound ->
                if (noViewFound != null) throw noViewFound
                (view as MapView).getMapAsync { map ->
                    map.getStyle { style ->
                        runCatching {
                            assertThat(style.getSource("aus-roads-tiles")).isNotNull()
                            assertThat(style.getLayer("road-primary")).isNotNull()
                        }.onFailure(callbackFailure::set)
                        styleReady.countDown()
                    }
                }
            }

            assertThat(styleReady.await(MAP_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            callbackFailure.get()?.let { throw AssertionError("Map style callback failed", it) }
        }
    }

    private companion object {
        const val MAP_READY_TIMEOUT_SECONDS = 20L
    }
}
