package au.com.ausroads.ui.map

import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.InstalledPack
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class MapPackStateViewModelTest {
    private fun pack(version: String) = InstalledPack(
        version = version, regionCode = "AU-SA", installedAt = Instant.fromEpochMilliseconds(0),
        totalSizeBytes = 1L, tilesPath = "tiles.mbtiles", searchPath = null, routingPath = null,
        manifestSha256 = "a".repeat(64),
    )

    private fun viewModel(flow: MutableStateFlow<InstalledPack?>, health: PlacesHealth = mockk(relaxed = true)) =
        MapPackStateViewModel(mockk<MapPackManager> { every { installed } returns flow }, health)

    @Test fun `installed forwards the manager flow unchanged`() = runTest {
        val flow = MutableStateFlow<InstalledPack?>(null)
        val viewModel = viewModel(flow)
        assertThat(viewModel.installed).isSameInstanceAs(flow)
        assertThat(viewModel.installed.value).isNull()
    }

    @Test fun `installed reacts when a pack is installed`() = runTest {
        val flow = MutableStateFlow<InstalledPack?>(null)
        val viewModel = viewModel(flow)
        flow.value = pack("2026-08-22")
        assertThat(viewModel.installed.value?.version).isEqualTo("2026-08-22")
    }

    @Test fun `installed reacts when a pack is uninstalled`() = runTest {
        val flow = MutableStateFlow<InstalledPack?>(pack("2026-08-22"))
        val viewModel = viewModel(flow)
        flow.value = null
        assertThat(viewModel.installed.value).isNull()
    }

    @Test fun assetAndStyleOutcomesNameTheirOwnPersistentHealthSeams() {
        val health = mockk<PlacesHealth>(relaxed = true)
        val vm = viewModel(MutableStateFlow(null), health)
        vm.recordAssets(false)
        vm.recordAssets(true)
        vm.recordStyle(false)
        vm.recordStyle(true)
        verify(exactly = 1) { health.failure(PlacesSeam.MAP_ASSETS) }
        verify(exactly = 1) { health.success(PlacesSeam.MAP_ASSETS) }
        verify(exactly = 1) { health.failure(PlacesSeam.MAP_STYLE) }
        verify(exactly = 1) { health.success(PlacesSeam.MAP_STYLE) }
    }
}
