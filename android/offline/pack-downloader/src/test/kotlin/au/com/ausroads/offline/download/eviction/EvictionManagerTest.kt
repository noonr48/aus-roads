package au.com.ausroads.offline.download.eviction

import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat
import java.io.File

class EvictionManagerTest {

    private lateinit var tempDir: File
    private lateinit var packStateStore: PackStateStore
    private lateinit var evictionManager: EvictionManager

    private val packV1 = InstalledPack(
        version = "1",
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-01-01T00:00:00Z"),
        totalSizeBytes = 100_000_000,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "abc",
    )

    private val packV2 = InstalledPack(
        version = "2",
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-02-01T00:00:00Z"),
        totalSizeBytes = 120_000_000,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "def",
    )

    @Before
    fun setUp() {
        tempDir = createTempDir("eviction-test")
        packStateStore = mockk(relaxed = true)
        every { packStateStore.packDir(any()) } answers {
            File(tempDir, "v${firstArg<String>()}")
        }
        evictionManager = EvictionManager(packStateStore)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `reconcile keeps current and previous versions`() = runTest {
        val currentDir = File(tempDir, "v1")
        val previousDir = File(tempDir, "v2")
        val staleDir = File(tempDir, "v0")
        currentDir.mkdirs()
        previousDir.mkdirs()
        staleDir.mkdirs()

        coEvery { packStateStore.readCurrent() } returns packV1
        coEvery { packStateStore.readPrevious() } returns packV2

        evictionManager.reconcile()

        assertThat(currentDir.exists()).isTrue()
        assertThat(previousDir.exists()).isTrue()
        assertThat(staleDir.exists()).isFalse()
    }

    @Test
    fun `reconcile reverts to previous when current dir is missing`() = runTest {
        val previousDir = File(tempDir, "v2")
        previousDir.mkdirs()

        coEvery { packStateStore.readCurrent() } returns packV1
        coEvery { packStateStore.readPrevious() } returns packV2

        evictionManager.reconcile()

        coVerify { packStateStore.writeCurrent(packV2) }
        coVerify { packStateStore.writePrevious(null) }
    }

    @Test
    fun `reconcile clears state when both current and previous dirs are missing`() = runTest {
        coEvery { packStateStore.readCurrent() } returns packV1
        coEvery { packStateStore.readPrevious() } returns packV2

        evictionManager.reconcile()

        coVerify {
            packStateStore.writeCurrent(match { it.version == "" && it.totalSizeBytes == 0L })
        }
        coVerify { packStateStore.writePrevious(null) }
    }

    @Test
    fun `onNewInstall promotes current to previous`() = runTest {
        coEvery { packStateStore.readCurrent() } returns packV1

        evictionManager.onNewInstall(packV2)

        coVerify { packStateStore.writePrevious(packV1) }
        coVerify { packStateStore.writeCurrent(packV2) }
    }

    @Test
    fun `onNewInstall deletes stale version dirs`() = runTest {
        val staleDir = File(tempDir, "v0")
        staleDir.mkdirs()
        coEvery { packStateStore.readCurrent() } returns packV1

        evictionManager.onNewInstall(packV2)

        assertThat(staleDir.exists()).isFalse()
    }
}
