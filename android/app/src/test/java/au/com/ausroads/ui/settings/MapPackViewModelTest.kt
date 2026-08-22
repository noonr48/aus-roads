package au.com.ausroads.ui.settings

import android.content.Context
import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.ManifestFetchResult
import au.com.ausroads.offline.pack.OsmSource
import au.com.ausroads.offline.pack.PackComponents
import au.com.ausroads.offline.pack.PackManifest
import au.com.ausroads.offline.pack.RoutingComponent
import au.com.ausroads.offline.pack.SearchComponent
import au.com.ausroads.offline.pack.TileComponent
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapPackViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mapPackManager: MapPackManager
    private lateinit var viewModel: MapPackViewModel

    private val installedFlow = MutableStateFlow<InstalledPack?>(null)
    private val inFlightFlow = MutableStateFlow<DownloadProgress?>(null)
    private val downloadErrorFlow = MutableStateFlow<String?>(null)

    private val baseUrl = "https://cdn.aus-roads.example"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mapPackManager = mockk(relaxed = true)
        every { mapPackManager.installed } returns installedFlow
        every { mapPackManager.inFlight } returns inFlightFlow
        every { mapPackManager.downloadError } returns downloadErrorFlow
        val context = mockk<Context>(relaxed = true)
        viewModel = MapPackViewModel(context, mapPackManager, baseUrl)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has no installed pack`() = runTest {
        assertThat(viewModel.installed.value).isNull()
    }

    @Test
    fun `onDownloadClick triggers manifest fetch`() = runTest {
        val manifest = buildManifest()
        coEvery { mapPackManager.fetchLatestManifest() } returns
            ManifestFetchResult.Fresh(manifest, "{}")

        viewModel.onDownloadClick()

        verify { mapPackManager.startDownload(any(), "2026-06-01", any()) }
        assertThat(viewModel.uiState.value.isChecking).isFalse()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `onDownloadClick builds pack url from injected base url`() = runTest {
        val manifest = buildManifest()
        coEvery { mapPackManager.fetchLatestManifest() } returns
            ManifestFetchResult.Fresh(manifest, "{}")
        val urlSlot = slot<String>()
        every { mapPackManager.startDownload(capture(urlSlot), any(), any()) } just runs

        viewModel.onDownloadClick()

        assertThat(urlSlot.captured)
            .isEqualTo("https://cdn.aus-roads.example/pack.zip")
    }

    @Test
    fun `downloadError from manager is exposed`() = runTest {
        downloadErrorFlow.value = "Verification failed: tiles"
        assertThat(viewModel.downloadError.value).isEqualTo("Verification failed: tiles")
    }

    @Test
    fun `onDownloadClick re-downloads on Unchanged when installed version differs`() = runTest {
        // 304 Unchanged must still download when the installed pack is an older version
        // — a download that failed after the manifest was cached stays retryable.
        installedFlow.value = installedPack(version = "2026-05-31")
        coEvery { mapPackManager.fetchLatestManifest() } returns
            ManifestFetchResult.Unchanged(buildManifest(), "{}")

        viewModel.onDownloadClick()

        verify { mapPackManager.startDownload(any(), "2026-06-01", any()) }
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `onDownloadClick does not download on Unchanged when installed version matches`() = runTest {
        installedFlow.value = installedPack(version = "2026-06-01")
        coEvery { mapPackManager.fetchLatestManifest() } returns
            ManifestFetchResult.Unchanged(buildManifest(), "{}")

        viewModel.onDownloadClick()

        verify(exactly = 0) { mapPackManager.startDownload(any(), any(), any()) }
        // "Already up to date" is an outcome, not a failure: info channel, no error.
        assertThat(viewModel.uiState.value.info).isNotNull()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `onRetryClick re-enqueues last failed download without refetching manifest`() = runTest {
        val manifest = buildManifest()
        coEvery { mapPackManager.fetchLatestManifest() } returns
            ManifestFetchResult.Fresh(manifest, "{}")
        viewModel.onDownloadClick()
        // Simulate a failed worker run after enqueue.
        downloadErrorFlow.value = "Verification failed: tiles"

        viewModel.onRetryClick()

        coVerify(exactly = 1) { mapPackManager.fetchLatestManifest() }
        verify(exactly = 2) {
            mapPackManager.startDownload("https://cdn.aus-roads.example/pack.zip", "2026-06-01", any())
        }
    }

    @Test
    fun `onRetryClick without prior attempt falls back to manifest check`() = runTest {
        val manifest = buildManifest()
        coEvery { mapPackManager.fetchLatestManifest() } returns
            ManifestFetchResult.Fresh(manifest, "{}")

        viewModel.onRetryClick()

        coVerify(exactly = 1) { mapPackManager.fetchLatestManifest() }
        verify(exactly = 1) { mapPackManager.startDownload(any(), "2026-06-01", any()) }
    }

    @Test
    fun `new check clears previous info message`() = runTest {
        installedFlow.value = installedPack(version = "2026-06-01")
        coEvery { mapPackManager.fetchLatestManifest() } returns
            ManifestFetchResult.Unchanged(buildManifest(), "{}")
        viewModel.onDownloadClick()
        assertThat(viewModel.uiState.value.info).isNotNull()

        // A newer pack becomes available and the user checks again.
        installedFlow.value = installedPack(version = "2026-05-31")
        coEvery { mapPackManager.fetchLatestManifest() } returns
            ManifestFetchResult.Fresh(buildManifest(), "{}")
        viewModel.onDownloadClick()

        assertThat(viewModel.uiState.value.info).isNull()
        verify { mapPackManager.startDownload(any(), "2026-06-01", any()) }
    }

    private fun installedPack(version: String) = InstalledPack(
        version = version,
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-05-31T00:00:00Z"),
        totalSizeBytes = 100L,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "abc",
    )

    private fun buildManifest() = PackManifest(
        packVersion = "2026-06-01",
        region = Region.AU_SA,
        bbox = Bbox(129.0, -38.0, 141.0, -26.0),
        generatedAt = Instant.parse("2026-06-01T00:00:00Z"),
        osmSource = OsmSource("geofabrik", "https://example.com/sa.osm.pbf", Instant.parse("2026-05-30T00:00:00Z")),
        minAppVersion = "0.1.0",
        minAndroidSdk = 26,
        components = PackComponents(
            tiles = TileComponent("pmtiles", "v1", 0, 14, "tiles.pmtiles", 100L, "abc123"),
            routing = RoutingComponent("valhalla", "auto", "routing.tar", 200L, "def456"),
            search = SearchComponent("sqlite", "search.db", 50L, "ghi789"),
        ),
        totalSizeBytes = 350L,
    )

    @Test
    fun `uninstall confirmation flow deletes the pack and surfaces info`() = runTest {
        coEvery { mapPackManager.deleteInstalled() } returns true

        viewModel.onUninstallClick()
        assertThat(viewModel.uiState.value.showUninstallConfirm).isTrue()

        viewModel.onUninstallConfirmed()

        coVerify(exactly = 1) { mapPackManager.deleteInstalled() }
        val state = viewModel.uiState.value
        assertThat(state.showUninstallConfirm).isFalse()
        assertThat(state.isDeleting).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.info).isNotNull()
    }

    @Test
    fun `onUninstallDismissed hides dialog without deleting`() = runTest {
        viewModel.onUninstallClick()
        viewModel.onUninstallDismissed()

        assertThat(viewModel.uiState.value.showUninstallConfirm).isFalse()
        coVerify(exactly = 0) { mapPackManager.deleteInstalled() }
    }


    @Test
    fun `uninstall failure surfaces an error instead of success info`() = runTest {
        coEvery { mapPackManager.deleteInstalled() } returns false

        viewModel.onUninstallClick()
        viewModel.onUninstallConfirmed()

        val state = viewModel.uiState.value
        assertThat(state.isDeleting).isFalse()
        assertThat(state.error).isNotNull()
        assertThat(state.info).isNull()
    }

}
