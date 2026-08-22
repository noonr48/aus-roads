package au.com.ausroads.ui.map

import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.InstalledPack
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class MapPackStateViewModelTest {

    private fun pack(version: String) = InstalledPack(
        version = version,
        regionCode = "AU-SA",
        installedAt = Instant.fromEpochMilliseconds(0),
        totalSizeBytes = 1L,
        tilesPath = "/data/tiles",
        searchPath = null,
        routingPath = null,
        manifestSha256 = "a".repeat(64),
    )

    @Test
    fun `installed forwards the manager flow unchanged`() = runTest {
        val flow = MutableStateFlow<InstalledPack?>(null)
        val manager = mockk<MapPackManager> { every { installed } returns flow }
        val viewModel = MapPackStateViewModel(manager)

        assertThat(viewModel.installed).isSameInstanceAs(flow)
        assertThat(viewModel.installed.value).isNull()
    }

    @Test
    fun `installed reacts when a pack is installed`() = runTest {
        val flow = MutableStateFlow<InstalledPack?>(null)
        val manager = mockk<MapPackManager> { every { installed } returns flow }
        val viewModel = MapPackStateViewModel(manager)

        flow.value = pack("2026-08-22")

        assertThat(viewModel.installed.value).isNotNull()
        assertThat(viewModel.installed.value?.version).isEqualTo("2026-08-22")
    }

    @Test
    fun `installed reacts when a pack is uninstalled`() = runTest {
        val flow = MutableStateFlow<InstalledPack?>(pack("2026-08-22"))
        val manager = mockk<MapPackManager> { every { installed } returns flow }
        val viewModel = MapPackStateViewModel(manager)

        flow.value = null

        assertThat(viewModel.installed.value).isNull()
    }
}
