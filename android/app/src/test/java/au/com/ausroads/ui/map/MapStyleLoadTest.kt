package au.com.ausroads.ui.map

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

@OptIn(ExperimentalCoroutinesApi::class)
class MapStyleLoadTest {
    @Before fun mainClock() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun restoreMainClock() { Dispatchers.resetMain() }
    @Test fun missingNativeCompletionRefusesWithNamedStyleSeam() = runTest {
        val map = mockk<MapLibreMap>()
        every { map.setStyle(any<Style.Builder>(), any<Style.OnStyleLoaded>()) } returns Unit
        val error = runCatching { map.awaitPreparedStyle("{}") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error!!.message).isEqualTo("component=map seam=style-load class=not-joined")
    }

    @Test fun heldVendorCallbackCannotPublishAfterCancellationButHealthyCallbackCompletes() = runTest {
        val map = mockk<MapLibreMap>()
        val style = mockk<Style>()
        val callbacks = mutableListOf<Style.OnStyleLoaded>()
        every { map.setStyle(any<Style.Builder>(), any<Style.OnStyleLoaded>()) } answers {
            callbacks += secondArg<Style.OnStyleLoaded>()
            Unit
        }
        var publications = 0
        val canceled = launch { map.awaitPreparedStyle("{}"); publications++ }
        runCurrent()
        assertThat(callbacks).hasSize(1)
        assertThat(canceled.isActive).isTrue() // prove cancellation happens BEFORE callback delivery
        canceled.cancel()
        runCurrent()
        callbacks[0].onStyleLoaded(style)
        runCurrent()
        assertThat(publications).isEqualTo(0)
        val healthy = launch { map.awaitPreparedStyle("{}"); publications++ }
        runCurrent()
        assertThat(healthy.isActive).isTrue()
        callbacks[1].onStyleLoaded(style)
        runCurrent()
        assertThat(publications).isEqualTo(1)
        assertThat(healthy.isCompleted).isTrue()
    }
}
