package au.com.ausroads.offline.download

import android.content.Context
import androidx.work.WorkManager
import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.manifest.ManifestFetcher
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regression guards for the delete/suppression protocol: the SUCCEEDED observer's
 * restore and [MapPackManager.deleteInstalled]'s tombstone must be atomic against
 * each other (one shared lock), so a racing worker completion can neither
 * resurrect a deleted version in memory nor leave its directory orphaned on disk.
 */
class MapPackManagerTest {

    // MapPackManager's worker-success observer path touches Dispatchers.Main;
    // JVM unit tests must supply one (house pattern: MapPackViewModelTest).
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var tempDir: File
    private lateinit var manager: MapPackManager
    private lateinit var packStateStore: PackStateStore
    private lateinit var baseDir: File

    private val version = "2026-08-22"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = createTempDir("map-pack-manager-test")
        // JVM unit test: no real WorkManager initializer — stub the static lookup.
        // The observer collector then parks on an inactive LiveData flow harmlessly.
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockk(relaxed = true)
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        packStateStore = PackStateStore(context)
        baseDir = File(tempDir, "mappacks/au-sa")
        manager = MapPackManager(
            context = context,
            manifestFetcher = mockk(relaxed = true),
            packStateStore = packStateStore,
            evictionManager = mockk(relaxed = true),
            // Same singleton mutex production DI injects into EvictionManager.
            stateMutex = Mutex(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
        tempDir.deleteRecursively()
    }

    @Test
    fun `success observer adopts the installed state from disk`() = runTest {
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)

        manager.adoptRestoredPack()

        assertThat(manager.installed.value?.version).isEqualTo(version)
    }

    @Test
    fun `late observer restore after uninstall never resurrects the deleted version`() = runTest {
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)
        manager.adoptRestoredPack()

        val deleted = manager.deleteInstalled()
        assertThat(deleted).isTrue()
        assertThat(manager.installed.value).isNull()

        // Simulate the racing worker's finishing tail rewriting persisted state
        // AFTER the uninstall; the observer must trip the tombstone, purge the
        // resurrected DIRECTORY (not just current.json), and keep memory clean.
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)

        manager.adoptRestoredPack()

        assertThat(manager.installed.value).isNull()
        assertThat(packStateStore.readCurrent()).isNull()
        assertThat(File(baseDir, "v$version").exists()).isFalse()
        assertThat(baseDir.exists()).isFalse()
    }

    @Test
    fun `interleaved restore and delete cannot leave memory ahead of disk`() = runTest {
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)
        // Establish the in-memory installed state synchronously so deleteInstalled
        // definitely has a target; then race both critical sections.
        manager.adoptRestoredPack()

        val restore = async { manager.adoptRestoredPack() }
        val delete = async { manager.deleteInstalled() }
        awaitAll(restore, delete)

        // Whatever lock acquisition order occurred, published memory must mirror
        // the persisted state — the pre-fix race ended with installed != null while
        // current.json/dir were already gone.
        val diskCurrent = packStateStore.readCurrent()
        if (diskCurrent == null) {
            assertThat(manager.installed.value).isNull()
        } else {
            assertThat(manager.installed.value?.version).isEqualTo(diskCurrent.version)
            assertThat(File(baseDir, "v${diskCurrent.version}").exists()).isTrue()
        }
    }

    private fun pack(forVersion: String) = InstalledPack(
        version = forVersion,
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-08-22T00:00:00Z"),
        totalSizeBytes = 1L,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "abc",
    )

    private fun seedVersionDir(forVersion: String) {
        File(baseDir, "v$forVersion").apply { mkdirs() }
            .resolve("tiles.mbtiles").writeText("tile-data-$forVersion")
    }
}
