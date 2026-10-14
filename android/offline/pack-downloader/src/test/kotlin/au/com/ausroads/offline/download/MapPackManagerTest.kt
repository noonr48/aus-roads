package au.com.ausroads.offline.download

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.manifest.ManifestFetcher
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Regression guards for the delete/suppression protocol: the SUCCEEDED observer's
 * restore and [MapPackManager.deleteInstalled]'s tombstone must be atomic against
 * each other (one shared lock), so a racing worker completion can neither
 * resurrect a deleted version in memory nor leave its directory orphaned on disk.
 *
 * Work-state tests execute the production manager query/collector, public enqueue/
 * cancel and real pack-state IO, with controlled WorkManager/WorkInfo inputs and
 * mocked LiveData-to-Flow transport. emit() acknowledges AFTER collect returns,
 * so negative assertions cannot pass merely because late input was not processed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapPackManagerTest {

    // MapPackManager's worker-success observer path touches Dispatchers.Main;
    // JVM unit tests must supply one (house pattern: MapPackViewModelTest).
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var tempDir: File
    private lateinit var manager: MapPackManager
    private lateinit var packStateStore: PackStateStore
    private lateinit var baseDir: File
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var updates: Channel<WorkSnapshot>
    private val requests = mutableListOf<OneTimeWorkRequest>()
    private val failureLogs = CopyOnWriteArrayList<String>()
    private var startedAtMillis = 0L

    private val version = "2026-08-22"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = createTempDir("map-pack-manager-test")
        startedAtMillis = System.currentTimeMillis()
        mockkStatic(Log::class)
        every { Log.e("MapPackManager", capture(failureLogs)) } returns 0
        mockkStatic(WorkManager::class)
        mockkStatic("androidx.lifecycle.FlowLiveDataConversions")
        workManager = mockk()
        every { WorkManager.getInstance(any()) } returns workManager
        val liveData = mockk<LiveData<List<WorkInfo>>>()
        updates = Channel(Channel.UNLIMITED)
        every { workManager.getWorkInfosForUniqueWorkLiveData("pack-download") } returns liveData
        every { liveData.asFlow() } returns flow {
            for (snapshot in updates) {
                emit(snapshot.infos)
                snapshot.handled.complete(Unit)
            }
        }
        val operation = mockk<Operation>()
        every { operation.result } returns Futures.immediateFuture(Operation.SUCCESS)
        every { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, capture(requests)) } returns operation
        every { workManager.cancelUniqueWork("pack-download") } returns operation
        context = mockk()
        every { context.filesDir } returns tempDir
        every { context.noBackupFilesDir } returns File(tempDir, "no-backup")
        packStateStore = PackStateStore(context)
        baseDir = File(tempDir, "mappacks/au-sa")
        manager = newManager()
    }

    @After
    fun tearDown() = runBlocking {
        stopManager()
        updates.close()
        Dispatchers.resetMain()
        unmockkAll()
        tempDir.deleteRecursively()
        Unit
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

    @Test
    fun `boot empty work is idle and unique queued work outranks history in either order`() = runTest {
        emitWork()
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
        val id = UUID.randomUUID()
        val old = info(UUID.randomUUID(), WorkInfo.State.FAILED, error = "old failure")
        val queued = info(id, WorkInfo.State.ENQUEUED, bytes = 200, total = 400)
        emitWork(old, queued)
        assertThat(manager.inFlight.value).isEqualTo(DownloadProgress(DownloadProgress.Phase.QUEUED, 0))
        emitWork(queued, old)
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(manager.downloadError.value).isNull()
        verify(exactly = 1) { workManager.getWorkInfosForUniqueWorkLiveData("pack-download") }
    }

    @Test
    fun `queued running requeued and blocked share the real progress owner without stale byte claims`() = runTest {
        val id = UUID.randomUUID()
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        emitWork(info(id, WorkInfo.State.RUNNING, bytes = 2048, total = 4096))
        assertThat(manager.inFlight.value).isEqualTo(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, 2048, 4096))
        emitWork(info(id, WorkInfo.State.ENQUEUED, bytes = 2048, total = 4096))
        assertThat(manager.inFlight.value).isEqualTo(DownloadProgress(DownloadProgress.Phase.QUEUED, 0))
        assertThat(manager.inFlight.value?.percent).isNull()
        emitWork(info(id, WorkInfo.State.BLOCKED))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        emitWork(info(id, WorkInfo.State.CANCELLED))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `failed output is surfaced and cancelled clears both failure and busy`() = runTest {
        val id = UUID.randomUUID()
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        emitWork(info(id, WorkInfo.State.FAILED, error = "verification failed"))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isEqualTo("verification failed")
        emitWork(info(id, WorkInfo.State.CANCELLED))
        assertThat(manager.downloadError.value).isNull()
        emitWork(info(id, WorkInfo.State.FAILED))
        assertThat(manager.downloadError.value).isEqualTo(DOWNLOAD_ERROR_FALLBACK)
        awaitMarker("OBSERVER", "WORK_FAILED", count = 2, generation = 0)
        emitWork(info(id, WorkInfo.State.SUCCEEDED))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
        assertThat(manager.installed.value).isEqualTo(packStateStore.readCurrent())
    }

    @Test
    fun `queued Cancel fences late queued running and failed snapshots even after a new request`() = runTest {
        val cancelledId = UUID.randomUUID()
        emitWork(info(cancelledId, WorkInfo.State.ENQUEUED))
        manager.cancelDownload()
        assertThat(manager.inFlight.value).isNull()
        emitWork(info(cancelledId, WorkInfo.State.ENQUEUED))
        emitWork(info(cancelledId, WorkInfo.State.RUNNING))
        emitWork(info(cancelledId, WorkInfo.State.FAILED, error = "late failure"))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()

        manager.startDownload("https://example.invalid/pack.zip", version)
        val replacementId = requests.single().id
        emitWork(info(cancelledId, WorkInfo.State.ENQUEUED), info(replacementId, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        emitWork(info(cancelledId, WorkInfo.State.FAILED, error = "stale failure"))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(manager.downloadError.value).isNull()
        emitWork(info(replacementId, WorkInfo.State.RUNNING, bytes = 2, total = 4))
        assertThat(manager.inFlight.value?.bytesDownloaded).isEqualTo(2)
        verify(exactly = 1) { workManager.cancelUniqueWork("pack-download") }
    }

    @Test
    fun `Cancel before first observation cannot be undone by that submitted request`() = runTest {
        manager.startDownload("https://example.invalid/pack.zip", version)
        manager.cancelDownload()
        emitWork(info(requests.single().id, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `KEEP retained work remains the owner rather than the discarded submitted ID`() = runTest {
        val retainedId = UUID.randomUUID()
        emitWork(info(retainedId, WorkInfo.State.ENQUEUED))
        manager.startDownload("https://example.invalid/pack.zip", version)
        assertThat(requests.single().id).isNotEqualTo(retainedId)
        emitWork(info(retainedId, WorkInfo.State.RUNNING, bytes = 10, total = 20))
        assertThat(manager.inFlight.value?.bytesDownloaded).isEqualTo(10)
        verify(exactly = 1) { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        manager.cancelDownload()
        emitWork(info(retainedId, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value).isNull()
    }

    @Test
    fun `boot current authority and success adoption never clear a different queued owner`() = runTest {
        stopManager()
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)
        manager = newManager()
        withContext(Dispatchers.IO) { withTimeout(5000) { manager.installed.first { it != null } } }
        val activeId = UUID.randomUUID()
        val history = info(UUID.randomUUID(), WorkInfo.State.SUCCEEDED)
        emitWork(history, info(activeId, WorkInfo.State.ENQUEUED))
        assertThat(manager.installed.value).isEqualTo(packStateStore.readCurrent())
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        val replacement = "2026-08-23"
        packStateStore.writeCurrent(pack(replacement))
        seedVersionDir(replacement)
        emitWork(history, info(activeId, WorkInfo.State.SUCCEEDED))
        assertThat(manager.installed.value).isEqualTo(packStateStore.readCurrent())
        assertThat(manager.installed.value?.version).isEqualTo(replacement)
        assertThat(manager.currentPackDir()).isEqualTo(File(baseDir, "v$replacement"))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `actual success collector still suppresses disk resurrection after delete and Cancel fence`() = runTest {
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)
        val history = info(UUID.randomUUID(), WorkInfo.State.SUCCEEDED)
        emitWork(history)
        val id = UUID.randomUUID()
        emitWork(history, info(id, WorkInfo.State.ENQUEUED))
        assertThat(manager.deleteInstalled()).isTrue()
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)
        // The same cancelled owner really finished; current.json still owns adoption.
        emitWork(history, info(id, WorkInfo.State.SUCCEEDED))
        assertThat(manager.installed.value).isNull()
        assertThat(packStateStore.readCurrent()).isNull()
        assertThat(File(baseDir, "v$version").exists()).isFalse()
        assertThat(manager.inFlight.value).isNull()
    }

    @Test
    fun `unknown running phase falls back and leaves a payload free persistent observer witness`() = runTest {
        emitWork(info(UUID.randomUUID(), WorkInfo.State.RUNNING, phase = "private invalid phase"))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.DOWNLOADING)
        assertThat(manager.downloadError.value).isNull()
        awaitMarker("OBSERVER", "UNKNOWN_PHASE", generation = 0)
        emitWork(info(UUID.randomUUID(), WorkInfo.State.RUNNING, phase = "QUEUED"))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.DOWNLOADING)
        awaitMarker("OBSERVER", "UNKNOWN_PHASE", count = 2, generation = 0)
    }

    @Test
    fun `enqueue async rejection persists a fixed witness and surfaces a retryable failure`() = runTest {
        val result = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation>()
        every { operation.result } returns result
        every { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, capture(requests)) } returns operation
        manager.startDownload("https://example.invalid/private.zip", "private-version")
        result.setException(IllegalStateException("private operation detail"))
        withContext(Dispatchers.IO) { withTimeout(5000) { manager.downloadError.first { it != null } } }
        assertThat(manager.downloadError.value).isEqualTo(DOWNLOAD_ERROR_FALLBACK)
        assertThat(manager.inFlight.value).isNull()
        awaitMarker("ENQUEUE", generation = 1)
    }

    @Test
    fun `late enqueue rejection after Cancel records failure without resurrecting an error`() = runTest {
        val result = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation>()
        every { operation.result } returns result
        every { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, capture(requests)) } returns operation
        manager.startDownload("https://example.invalid/pack.zip", version)
        manager.cancelDownload()
        result.setException(IllegalStateException("late operation failure"))
        awaitMarker("ENQUEUE", generation = 1)
        emitWork(info(requests.single().id, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `synchronous cancel and enqueue failures have distinct persistent witnesses`() = runTest {
        emitWork(info(UUID.randomUUID(), WorkInfo.State.ENQUEUED))
        every { workManager.cancelUniqueWork("pack-download") } throws IllegalStateException("private cancel detail")
        manager.cancelDownload()
        awaitMarker("CANCEL", generation = 1)
        // A rejected operation did not cancel the real work: retain its Cancel owner.
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
        every { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } throws
            IllegalStateException("private enqueue detail")
        manager.startDownload("https://example.invalid/private.zip", "private-version")
        awaitMarker("ENQUEUE", generation = 2)
        assertThat(manager.downloadError.value).isEqualTo(DOWNLOAD_ERROR_FALLBACK)
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(File(tempDir, "no-backup").list()?.toSet()).isEqualTo(
            setOf("pack-download-CANCEL.failed", "pack-download-ENQUEUE.failed"),
        )
    }

    @Test
    fun `async cancel rejection retains the work owner and truthful cancellation failure`() = runTest {
        val id = UUID.randomUUID()
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        val result = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation>()
        every { operation.result } returns result
        every { workManager.cancelUniqueWork("pack-download") } returns operation
        manager.cancelDownload()
        result.setException(IllegalStateException("private cancel detail"))
        awaitMarker("CANCEL", generation = 1)
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
    }

    @Test
    fun `observer registration failure persists without losing boot installed authority`() = runTest {
        stopManager()
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)
        every { workManager.getWorkInfosForUniqueWorkLiveData("pack-download") } throws
            IllegalStateException("private observer detail")
        manager = newManager()
        awaitMarker("OBSERVER", "OBSERVER_FAILED", generation = 0)
        withContext(Dispatchers.IO) { withTimeout(5000) { manager.installed.first { it != null } } }
        assertThat(manager.installed.value).isEqualTo(packStateStore.readCurrent())
    }

    @Test
    fun `rejected Cancel restores latest running bytes keeps failure through waiting and can cancel again without deleting pack bytes`() = runTest {
        packStateStore.writeCurrent(pack(version))
        seedVersionDir(version)
        manager.adoptRestoredPack()
        val tiles = File(baseDir, "v$version/tiles.mbtiles")
        val beforeTiles = tiles.readBytes()
        val beforeCurrent = packStateStore.readCurrent()
        val id = UUID.randomUUID()
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        val result = pendingCancelResult()
        manager.cancelDownload()
        emitWork(info(id, WorkInfo.State.RUNNING, bytes = 12, total = 24))
        assertThat(manager.inFlight.value).isNull()
        result.setException(IllegalStateException("private cancel detail"))
        awaitMarker("CANCEL", generation = 1)
        assertThat(manager.inFlight.value).isEqualTo(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, 12, 24))
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
        emitWork(info(id, WorkInfo.State.BLOCKED))
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value).isEqualTo(DownloadProgress(DownloadProgress.Phase.QUEUED, 0))
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
        every { workManager.cancelUniqueWork("pack-download") } returns successfulOperation()
        manager.cancelDownload()
        emitWork(info(id, WorkInfo.State.RUNNING, bytes = 18, total = 24))
        emitWork(info(id, WorkInfo.State.FAILED, error = "private late worker output"))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
        assertThat(packStateStore.readCurrent()).isEqualTo(beforeCurrent)
        assertThat(manager.installed.value).isEqualTo(beforeCurrent)
        assertThat(tiles.readBytes()).isEqualTo(beforeTiles)
        awaitMarker("CANCEL", generation = 1)
        verify(exactly = 2) { workManager.cancelUniqueWork("pack-download") }
    }

    @Test
    fun `rejected Cancel before first observation recovers only its submitted request`() = runTest {
        manager.startDownload("https://example.invalid/private.zip", "private-version")
        val id = requests.single().id
        every { workManager.cancelUniqueWork("pack-download") } throws IllegalStateException("private cancel detail")
        manager.cancelDownload()
        awaitMarker("CANCEL", generation = 2)
        assertThat(manager.inFlight.value).isNull() // No invented native work state.
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
        emitWork(info(UUID.randomUUID(), WorkInfo.State.FAILED, error = "private historical output"), info(id, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
        every { workManager.cancelUniqueWork("pack-download") } returns successfulOperation()
        manager.cancelDownload()
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `rejected Cancel restores KEEP retained owner not discarded request or historical output`() = runTest {
        val retainedId = UUID.randomUUID()
        emitWork(info(retainedId, WorkInfo.State.ENQUEUED))
        manager.startDownload("https://example.invalid/private.zip", "private-version")
        assertThat(requests.single().id).isNotEqualTo(retainedId)
        val result = pendingCancelResult()
        manager.cancelDownload()
        val history = info(UUID.randomUUID(), WorkInfo.State.FAILED, error = "private historical output")
        emitWork(history, info(retainedId, WorkInfo.State.RUNNING, bytes = 10, total = 20))
        result.setException(IllegalStateException("private cancel detail"))
        awaitMarker("CANCEL", generation = 2)
        assertThat(manager.inFlight.value).isEqualTo(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, 10, 20))
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
        emitWork(info(retainedId, WorkInfo.State.ENQUEUED), history)
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
        emitWork(history, info(retainedId, WorkInfo.State.CANCELLED))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `late rejected Cancel cannot restore or clear a newer queued intent or its worker failure`() = runTest {
        val oldId = UUID.randomUUID()
        emitWork(info(oldId, WorkInfo.State.ENQUEUED))
        val result = pendingCancelResult()
        manager.cancelDownload()
        manager.startDownload("https://example.invalid/new-private.zip", "new-private-version")
        val newId = requests.single().id
        emitWork(info(newId, WorkInfo.State.ENQUEUED), info(oldId, WorkInfo.State.RUNNING))
        result.setException(IllegalStateException("private old cancel detail"))
        awaitMarker("CANCEL", generation = 1)
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(manager.downloadError.value).isNull()
        emitWork(info(oldId, WorkInfo.State.FAILED, error = "private old output"))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        emitWork(info(newId, WorkInfo.State.FAILED, error = "private new output"))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isEqualTo("private new output")
        awaitMarker("OBSERVER", "WORK_FAILED", generation = 2)
    }

    @Test
    fun `late rejected Cancel cannot overwrite a newer failed enqueue intent`() = runTest {
        emitWork(info(UUID.randomUUID(), WorkInfo.State.ENQUEUED))
        val result = pendingCancelResult()
        manager.cancelDownload()
        every { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) } throws
            IllegalStateException("private new enqueue detail")
        manager.startDownload("https://example.invalid/new-private.zip", "new-private-version")
        awaitMarker("ENQUEUE", generation = 2)
        assertThat(manager.downloadError.value).isEqualTo(DOWNLOAD_ERROR_FALLBACK)
        result.setException(IllegalStateException("private old cancel detail"))
        awaitMarker("CANCEL", generation = 1)
        assertThat(manager.downloadError.value).isEqualTo(DOWNLOAD_ERROR_FALLBACK)
        assertThat(manager.inFlight.value).isNull()
    }

    @Test
    fun `late rejected Cancel cannot undo a newer healthy Cancel fence`() = runTest {
        val id = UUID.randomUUID()
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        val result = pendingCancelResult()
        manager.cancelDownload()
        every { workManager.cancelUniqueWork("pack-download") } returns successfulOperation()
        manager.cancelDownload()
        result.setException(IllegalStateException("private old cancel detail"))
        awaitMarker("CANCEL", generation = 1)
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        emitWork(info(id, WorkInfo.State.RUNNING))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `actual terminal state while Cancel is pending wins over later operation rejection`() = runTest {
        val states = listOf(WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED)
        states.forEachIndexed { index, state ->
            manager.startDownload("https://example.invalid/private.zip", "private-version")
            val id = requests.last().id
            emitWork(info(id, WorkInfo.State.ENQUEUED))
            val result = pendingCancelResult()
            manager.cancelDownload()
            emitWork(info(id, state, error = "private terminal output"))
            result.setException(IllegalStateException("private cancel detail"))
            awaitMarker("CANCEL", count = index + 1L, generation = (index + 1L) * 2)
            assertThat(manager.inFlight.value).isNull()
            assertThat(manager.downloadError.value).isEqualTo(if (state == WorkInfo.State.FAILED) "private terminal output" else null)
            assertThat(manager.installed.value).isEqualTo(packStateStore.readCurrent())
        }
        awaitMarker("OBSERVER", "WORK_FAILED", generation = 6)
    }

    @Test
    fun `late enqueue rejection cannot clear a newer submitted work owner`() = runTest {
        val result = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation>()
        every { operation.result } returns result
        every { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, capture(requests)) } returns operation
        manager.startDownload("https://example.invalid/old-private.zip", "old-private-version")
        every { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, capture(requests)) } returns successfulOperation()
        manager.startDownload("https://example.invalid/new-private.zip", "new-private-version")
        val id = requests.last().id
        emitWork(info(id, WorkInfo.State.RUNNING, bytes = 8, total = 16))
        result.setException(IllegalStateException("private old enqueue detail"))
        awaitMarker("ENQUEUE", generation = 1)
        assertThat(manager.inFlight.value).isEqualTo(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, 8, 16))
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `healthy work and Cancel emit no failure row or diagnostic`() = runTest {
        manager.startDownload("https://example.invalid/private.zip", "private-version")
        val id = requests.single().id
        emitWork(info(id, WorkInfo.State.ENQUEUED))
        emitWork(info(id, WorkInfo.State.BLOCKED))
        listOf("DOWNLOADING", "EXTRACTING", "VERIFYING", "INSTALLING").forEach { phase ->
            emitWork(info(id, WorkInfo.State.RUNNING, phase = phase))
            assertThat(manager.downloadError.value).isNull()
        }
        emitWork(info(id, WorkInfo.State.SUCCEEDED))
        manager.cancelDownload()
        emitWork(info(id, WorkInfo.State.CANCELLED))
        stopManager()
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
        assertThat(failureLogs).isEmpty()
        assertThat(File(tempDir, "no-backup").exists()).isFalse()
    }

    @Test
    fun `ambiguous unfinished boot work has a bounded witness without inventing an owner`() = runTest {
        val first = UUID.randomUUID()
        emitWork(info(first, WorkInfo.State.ENQUEUED), info(UUID.randomUUID(), WorkInfo.State.BLOCKED))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
        awaitMarker("OBSERVER", "AMBIGUOUS_WORK", generation = 0)
        emitWork(info(first, WorkInfo.State.ENQUEUED))
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.QUEUED)
        assertThat(manager.downloadError.value).isNull()
    }

    @Test
    fun `concurrent rejected callbacks cannot lose counts or publish truncated or private metadata`() = runTest {
        val results = List(12) { SettableFuture.create<Operation.State.SUCCESS>() }
        var next = 0
        every { workManager.cancelUniqueWork("pack-download") } answers {
            val operation = mockk<Operation>()
            every { operation.result } returns results[next++]
            operation
        }
        repeat(results.size) { manager.cancelDownload() }
        withContext(Dispatchers.Default) {
            results.map { result -> async { result.setException(IllegalStateException("private concurrent callback")) } }.awaitAll()
        }
        val row = awaitMarker("CANCEL", count = 12)
        assertThat(row.getValue("generation").jsonPrimitive.long).isIn(1L..12L)
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
        assertThat(manager.inFlight.value).isNull()
        assertThat(File(tempDir, "no-backup").list()?.toSet()).isEqualTo(setOf("pack-download-CANCEL.failed"))
        // The persistent counter includes all twelve failures, but the closed
        // seam/class emits only its five-line diagnostic budget.
        assertThat(failureLogs.count { "seam=CANCEL class=OPERATION_REJECTED" in it }).isEqualTo(5)
    }

    @Test
    fun `failure row count survives manager recreation by production filesystem readback`() = runTest {
        every { workManager.cancelUniqueWork("pack-download") } throws IllegalStateException("private cancel detail")
        manager.cancelDownload()
        awaitMarker("CANCEL", generation = 1)
        stopManager()
        manager = newManager()
        manager.cancelDownload()
        awaitMarker("CANCEL", count = 2, generation = 1)
        assertThat(manager.downloadError.value).isEqualTo("cancel_failed")
    }

    @Test
    fun `health read rejection names its class preserves the real prior row and excludes private payload`() = runTest {
        val id = UUID.randomUUID()
        emitWork(info(id, WorkInfo.State.RUNNING, phase = "private invalid phase"))
        awaitMarker("OBSERVER", "UNKNOWN_PHASE", generation = 0)
        val marker = File(tempDir, "no-backup/pack-download-OBSERVER.failed")
        val before = marker.readText()
        mockkStatic("kotlin.io.FilesKt__FileReadWriteKt")
        try {
            every { marker.readText(any()) } throws IllegalStateException("private health read path 138.6,-34.9")
            emitWork(info(id, WorkInfo.State.RUNNING, phase = "private invalid phase"))
            awaitLog("health-read", "HEALTH_READ_FAILED")
        } finally {
            unmockkStatic("kotlin.io.FilesKt__FileReadWriteKt")
        }
        assertThat(marker.readText()).isEqualTo(before)
        assertThat(manager.inFlight.value?.phase).isEqualTo(DownloadProgress.Phase.DOWNLOADING)
        assertThat(manager.downloadError.value).isNull()
        emitWork(info(id, WorkInfo.State.RUNNING, phase = "private invalid phase"))
        awaitMarker("OBSERVER", "UNKNOWN_PHASE", count = 2, generation = 0)
    }

    @Test
    fun `health atomic publish rejection names its class preserves the real prior row and recovers`() = runTest {
        val id = UUID.randomUUID()
        emitWork(info(id, WorkInfo.State.RUNNING, phase = "private invalid phase"))
        awaitMarker("OBSERVER", "UNKNOWN_PHASE", generation = 0)
        val marker = File(tempDir, "no-backup/pack-download-OBSERVER.failed")
        val before = marker.readText()
        mockkStatic(Files::class)
        try {
            every { Files.move(any(), any(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) } throws
                IllegalStateException("private health write path https://example.invalid/private")
            emitWork(info(id, WorkInfo.State.RUNNING, phase = "private invalid phase"))
            awaitLog("health-write", "HEALTH_WRITE_FAILED")
        } finally {
            unmockkStatic(Files::class)
        }
        assertThat(marker.readText()).isEqualTo(before)
        assertThat(manager.downloadError.value).isNull()
        emitWork(info(id, WorkInfo.State.RUNNING, phase = "private invalid phase"))
        awaitMarker("OBSERVER", "UNKNOWN_PHASE", count = 2, generation = 0)
        assertThat(File(tempDir, "no-backup").list()?.toSet()).isEqualTo(setOf("pack-download-OBSERVER.failed"))
    }

    @Test
    fun `rejected Cancel after an observed terminal cannot invent active recovery`() = runTest {
        listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED, WorkInfo.State.FAILED).forEachIndexed { index, state ->
            val id = UUID.randomUUID()
            emitWork(info(id, WorkInfo.State.ENQUEUED))
            emitWork(info(id, state, error = "private terminal output"))
            val result = pendingCancelResult()
            manager.cancelDownload()
            result.setException(IllegalStateException("private cancel detail"))
            awaitMarker("CANCEL", count = index + 1L, generation = index + 1L)
            assertThat(manager.inFlight.value).isNull()
            assertThat(manager.downloadError.value).isEqualTo(if (state == WorkInfo.State.FAILED) "private terminal output" else null)
        }
        awaitMarker("OBSERVER", "WORK_FAILED", count = 2, generation = 3)
    }

    @Test
    fun `enqueue rejection cannot erase actual observed native progress for the submitted request`() = runTest {
        val result = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation>()
        every { operation.result } returns result
        every { workManager.enqueueUniqueWork("pack-download", ExistingWorkPolicy.KEEP, capture(requests)) } returns operation
        manager.startDownload("https://example.invalid/private.zip", "private-version")
        val id = requests.single().id
        emitWork(info(id, WorkInfo.State.RUNNING, bytes = 12, total = 24))
        result.setException(IllegalStateException("private enqueue detail"))
        awaitMarker("ENQUEUE", generation = 1)
        assertThat(manager.inFlight.value).isEqualTo(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, 12, 24))
        assertThat(manager.downloadError.value).isEqualTo(DOWNLOAD_ERROR_FALLBACK)
        emitWork(info(id, WorkInfo.State.CANCELLED))
        assertThat(manager.inFlight.value).isNull()
        assertThat(manager.downloadError.value).isNull()
    }

    private fun successfulOperation(): Operation = mockk {
        every { result } returns Futures.immediateFuture(Operation.SUCCESS)
    }

    private fun pendingCancelResult(): SettableFuture<Operation.State.SUCCESS> {
        val result = SettableFuture.create<Operation.State.SUCCESS>()
        val operation = mockk<Operation>()
        every { operation.result } returns result
        every { workManager.cancelUniqueWork("pack-download") } returns operation
        return result
    }

    private data class WorkSnapshot(val infos: List<WorkInfo>, val handled: CompletableDeferred<Unit>)

    private suspend fun emitWork(vararg infos: WorkInfo) {
        val handled = CompletableDeferred<Unit>()
        updates.send(WorkSnapshot(infos.toList(), handled))
        withContext(Dispatchers.IO) { withTimeout(5000) { handled.await() } }
    }

    private fun info(
        id: UUID,
        state: WorkInfo.State,
        bytes: Long = 0,
        total: Long = -1,
        phase: String = "DOWNLOADING",
        error: String? = null,
    ): WorkInfo = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.state } returns state
        every { progress } returns Data.Builder().putLong("bytes", bytes).putLong("total", total).putString("phase", phase).build()
        every { outputData } returns Data.Builder().apply {
            error?.let { putString(MapPackDownloadWorker.KEY_RESULT_ERROR, it) }
        }.build()
    }

    private suspend fun awaitMarker(
        seam: String, failureClass: String = "OPERATION_REJECTED", count: Long = 1, generation: Long? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val marker = File(tempDir, "no-backup/pack-download-$seam.failed")
        var observed: JsonObject? = null
        withTimeout(5000) {
            while (observed == null) {
                if (marker.isFile) {
                    val row = Json.parseToJsonElement(marker.readText()).jsonObject
                    if (row.getValue("failureCount").jsonPrimitive.long == count) observed = row
                }
                if (observed == null) delay(10)
            }
        }
        // Read a production-created atomic row. Helpers never write asserted health state.
        val row = checkNotNull(observed)
        assertThat(marker.length()).isAtMost(1024L)
        assertThat(row.keys).containsExactly("schema", "component", "seam", "class", "failureCount", "lastFailureAtMillis", "generation")
        assertThat(row.getValue("schema").jsonPrimitive.int).isEqualTo(1)
        assertThat(row.getValue("component").jsonPrimitive.content).isEqualTo("map-pack")
        assertThat(row.getValue("seam").jsonPrimitive.content).isEqualTo(seam)
        assertThat(row.getValue("class").jsonPrimitive.content).isEqualTo(failureClass)
        assertThat(row.getValue("failureCount").jsonPrimitive.long).isEqualTo(count)
        val time = row.getValue("lastFailureAtMillis").jsonPrimitive.long
        assertThat(time).isAtLeast(startedAtMillis)
        assertThat(time).isAtMost(System.currentTimeMillis())
        val localGeneration = row.getValue("generation").jsonPrimitive.long
        assertThat(localGeneration).isAtLeast(0L)
        if (generation != null) assertThat(localGeneration).isEqualTo(generation)
        listOf("schema", "failureCount", "lastFailureAtMillis", "generation").forEach { key ->
            assertThat(row.getValue(key).jsonPrimitive.isString).isFalse()
        }
        val raw = row.toString()
        listOf("private", "https://", "version", "output", "138.6", "-34.9", "IllegalStateException", "stack").forEach { payload ->
            assertThat(raw).doesNotContain(payload)
        }
        assertPrivatePayloadFreeLogs()
        row
    }

    private suspend fun awaitLog(seam: String, failureClass: String) = withContext(Dispatchers.IO) {
        withTimeout(5000) { while (failureLogs.none { "seam=$seam class=$failureClass" in it }) delay(10) }
        assertPrivatePayloadFreeLogs()
    }

    private fun assertPrivatePayloadFreeLogs() {
        val closed = Regex("schema=1 component=map-pack seam=(OBSERVER|ENQUEUE|CANCEL|health-read|health-write) " +
            "class=(OPERATION_REJECTED|UNKNOWN_PHASE|WORK_FAILED|AMBIGUOUS_WORK|OBSERVER_FAILED|HEALTH_READ_FAILED|HEALTH_WRITE_FAILED) generation=[0-9]+")
        failureLogs.forEach { assertThat(closed.matches(it)).isTrue() }
    }

    private fun newManager() = MapPackManager(
        context = context,
        manifestFetcher = mockk<ManifestFetcher>(relaxed = true),
        packStateStore = packStateStore,
        evictionManager = mockk<EvictionManager>(relaxed = true),
        stateMutex = Mutex(),
    )

    private suspend fun stopManager() {
        // End the production lifetime BEFORE resetting mocks/Main/deleting files.
        val field = MapPackManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = field.get(manager) as CoroutineScope
        checkNotNull(scope.coroutineContext[Job]).cancelAndJoin()
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
