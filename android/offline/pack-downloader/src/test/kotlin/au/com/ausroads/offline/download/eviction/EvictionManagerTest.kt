package au.com.ausroads.offline.download.eviction

import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.offline.download.state.installDirectoryName
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        every { packStateStore.packDir(any<InstalledPack>()) } answers {
            File(tempDir, firstArg<InstalledPack>().installDirectoryName())
        }
        every { packStateStore.regionDir() } returns tempDir
        every { packStateStore.currentDescriptorExists() } returns false
        evictionManager = EvictionManager(packStateStore)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `reconcile keeps current and previous versions`() = runTest {
        val currentDir = createPackDirectory(packV1)
        val previousDir = createPackDirectory(packV2)
        val staleDir = File(tempDir, "v0")
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
        createPackDirectory(packV2)

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

        coVerify { packStateStore.clearCurrent() }
        coVerify { packStateStore.writePrevious(null) }
    }

    @Test
    fun `reconcile restores previous when current descriptor is malformed`() = runTest {
        createPackDirectory(packV2)
        coEvery { packStateStore.readCurrent() } returns null
        coEvery { packStateStore.readPrevious() } returns packV2
        every { packStateStore.currentDescriptorExists() } returns true

        evictionManager.reconcile()

        coVerify { packStateStore.writeCurrent(packV2) }
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

    @Test
    fun `failed activation restores previous physical generation and removes rejected bytes`() = runTest {
        val previous = packV1.copy(directoryName = "v1-old")
        val rejected = packV1.copy(
            installedAt = Instant.parse("2026-03-01T00:00:00Z"),
            directoryName = "v1-repair",
        )
        val previousDir = createPackDirectory(previous)
        val rejectedDir = createPackDirectory(rejected)
        coEvery { packStateStore.readCurrent() } returns rejected
        coEvery { packStateStore.readPrevious() } returns previous

        val restored = evictionManager.rollbackFailedInstall(rejected)

        assertThat(restored).isEqualTo(previous)
        assertThat(previousDir.exists()).isTrue()
        assertThat(rejectedDir.exists()).isFalse()
        coVerify { packStateStore.writeCurrent(previous) }
        coVerify { packStateStore.writePrevious(null) }
    }

    @Test
    fun `reconcile cannot delete a version promoted by concurrent install`() = runBlocking {
        val currentDir = createPackDirectory(packV1)
        val promotedDir = File(tempDir, "v2")
        val reconcileHoldingLock = CompletableDeferred<Unit>()
        val releaseReconcile = CompletableDeferred<Unit>()
        val promotionStarted = CompletableDeferred<Unit>()

        coEvery { packStateStore.readCurrent() } returns packV1
        coEvery { packStateStore.readPrevious() } coAnswers {
            reconcileHoldingLock.complete(Unit)
            releaseReconcile.await()
            null
        }

        val reconcile = async(Dispatchers.Default) { evictionManager.reconcile() }
        reconcileHoldingLock.await()
        val install = async(Dispatchers.Default) {
            evictionManager.onNewInstall(packV2) {
                promotionStarted.complete(Unit)
                promotedDir.mkdirs()
                null
            }
        }

        // Promotion is part of the same transaction and cannot begin while
        // reconciliation owns the mutation lock.
        val startedWhileReconciling = withContext(Dispatchers.Default) {
            withTimeoutOrNull(200) { promotionStarted.await() }
        }
        assertThat(startedWhileReconciling).isNull()

        releaseReconcile.complete(Unit)
        reconcile.await()
        assertThat(install.await()).isNull()

        assertThat(currentDir.exists()).isTrue()
        assertThat(promotedDir.exists()).isTrue()
        coVerify { packStateStore.writeCurrent(packV2) }
    }

    private fun createPackDirectory(pack: InstalledPack): File =
        File(tempDir, pack.installDirectoryName()).apply {
            mkdirs()
            File(this, pack.tilesPath).writeText("tiles")
        }
}
