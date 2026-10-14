package au.com.ausroads.offline.download

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.manifest.ManifestFetcher
import au.com.ausroads.offline.download.state.DOWNLOAD_CANCEL_FAILED
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.ManifestFetchResult
import au.com.ausroads.offline.download.state.PackStateStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapPackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manifestFetcher: ManifestFetcher,
    private val packStateStore: PackStateStore,
    private val evictionManager: EvictionManager,
    /**
     * Serializes every persisted-map-pack-state writer under one lock:
     * [deleteInstalled]'s tombstone (check-and-add to [suppressedAfterDelete]
     * plus state reset), the SUCCEEDED observer's restore ([adoptRestoredPack]),
     * and eviction's current.json/previous.json swaps all run inside it, so none
     * can interleave into another's read-check-publish sequence and resurrect
     * deleted-version state. Provided by DI as the singleton instance shared
     * with [EvictionManager] — internal for exactly that wiring.
     */
    internal val stateMutex: Mutex,
) {
    // Each closed seam/class has its own log budget; persisted counts remain complete.
    private val emittedFailures = ConcurrentHashMap<String, AtomicInteger>()
    // Keep installed/current authority on observer failure. No exception payload
    // crosses the diagnostic boundary; diagnostic IO/log failures are contained.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
            recordWorkFailure(WorkSeam.OBSERVER, WorkFailureClass.OBSERVER_FAILED, synchronized(workLock) { workGeneration })
        },
    )
    private val workManager = WorkManager.getInstance(context)

    private val _installed = MutableStateFlow<InstalledPack?>(null)
    val installed: StateFlow<InstalledPack?> = _installed.asStateFlow()

    private val _inFlight = MutableStateFlow<DownloadProgress?>(null)
    val inFlight: StateFlow<DownloadProgress?> = _inFlight.asStateFlow()

    /**
     * The most recent worker failure, or the closed [DOWNLOAD_CANCEL_FAILED]
     * sentinel for a rejected Cancel. A rejection is not proof that work stopped.
     * The UI maps these to fixed copy and the corresponding recovery action.
     */
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    // Only UI progress/error publication is guarded here; persisted pack writers
    // still use the shared stateMutex. KEEP may retain a different request ID.
    private val workLock = Any()
    private var ownedWorkId: UUID? = null
    private var ownedWorkInfo: WorkInfo? = null
    private var requestedWorkId: UUID? = null
    private var recoverUnownedWork = true
    private var workGeneration = 0L
    private var pendingCancel: CancelledWork? = null
    private val healthLock = Any()
    private val healthJson = Json

    private data class CancelledWork(
        val generation: Long,
        val ownedId: UUID?,
        val requestedId: UUID?,
        val recoverUnowned: Boolean,
        val progress: DownloadProgress?,
        var latest: WorkInfo? = null,
    )

    /**
     * Pack versions deleted via [deleteInstalled] whose completion events must
     * not resurrect state: a download worker finishing around an uninstall
     * re-writes current.json and would otherwise re-appear as installed.
     * Cleared per-version by [startDownload] (explicit re-install intent).
     */
    private val suppressedAfterDelete: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    init {
        scope.launch {
            evictionManager.reconcile()
            adoptRestoredPack()
        }

        // WorkManager's history is not ordered by recency. Recover only one
        // unfinished request at boot, or an ID actually submitted/observed here.
        scope.launch {
            var succeededIds = emptySet<UUID>()
            workManager.getWorkInfosForUniqueWorkLiveData("pack-download")
                .asFlow()
                .collect { infos ->
                    synchronized(workLock) {
                        // While Cancel is pending, retain only its latest native state,
                        // not visible progress. A rejected Cancel must not revive a
                        // worker that actually reached a terminal state meanwhile.
                        pendingCancel?.let { cancelled ->
                            selectWorkInfo(infos, cancelled.requestedId, cancelled.ownedId, cancelled.recoverUnowned)
                                ?.let { cancelled.latest = it }
                        }
                        val info = selectWorkInfo(infos, requestedWorkId, ownedWorkId, recoverUnownedWork)
                        if (info != null) {
                            publishWorkInfo(info)
                        } else if (infos.count { !it.state.isFinished } > 1 && recoverUnownedWork) {
                            recordWorkFailure(WorkSeam.OBSERVER, WorkFailureClass.AMBIGUOUS_WORK, workGeneration)
                        }
                    }
                    // Success adoption reads current.json under the existing shared
                    // lock, including after Cancel/delete. It is independent of the
                    // UI fence and never chooses an installed version from history.
                    val completed = infos.filter { it.state == WorkInfo.State.SUCCEEDED }.map { it.id }.toSet()
                    if (completed.any { it !in succeededIds }) adoptRestoredPack()
                    succeededIds = completed
                }
        }
    }

    suspend fun fetchLatestManifest(): ManifestFetchResult = manifestFetcher.fetch()

    fun startDownload(packUrl: String, packVersion: String, manifestJson: String? = null) {
        // Explicit (re-)install intent lifts any uninstall suppression.
        suppressedAfterDelete.remove(packVersion)
        val data = Data.Builder()
            .putString(MapPackDownloadWorker.KEY_PACK_URL, packUrl)
            .putString(MapPackDownloadWorker.KEY_PACK_VERSION, packVersion)
            .apply { manifestJson?.let { putString(MapPackDownloadWorker.KEY_MANIFEST_JSON, it) } }
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MapPackDownloadWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .addTag("pack-download")
            .build()

        synchronized(workLock) {
            workGeneration++
            pendingCancel = null
            requestedWorkId = request.id
            _downloadError.value = null
            runWorkOperation(WorkSeam.ENQUEUE, workGeneration, request.id) {
                workManager.enqueueUniqueWork("pack-download", androidx.work.ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    fun cancelDownload() {
        synchronized(workLock) {
            workGeneration++
            // Fence stale snapshots immediately, but keep the actual KEEP owner
            // for rejection recovery. Repeated Cancel supersedes the prior callback.
            pendingCancel = pendingCancel?.copy(generation = workGeneration) ?: CancelledWork(
                workGeneration, ownedWorkId, requestedWorkId, recoverUnownedWork, _inFlight.value, ownedWorkInfo,
            )
            recoverUnownedWork = false
            ownedWorkId = null
            ownedWorkInfo = null
            requestedWorkId = null
            _inFlight.value = null
            _downloadError.value = null
            runWorkOperation(WorkSeam.CANCEL, workGeneration) { workManager.cancelUniqueWork("pack-download") }
        }
    }

    /** Dismiss the surfaced worker error (e.g. after the user has seen it). */
    fun clearDownloadError() {
        synchronized(workLock) { _downloadError.value = null }
    }

    private fun selectWorkInfo(
        infos: List<WorkInfo>, requestedId: UUID?, ownedId: UUID?, recover: Boolean,
    ): WorkInfo? = infos.singleOrNull { it.id == requestedId }
        ?: infos.filter { !it.state.isFinished }.singleOrNull()?.takeIf { recover || it.id == ownedId }
        ?: infos.singleOrNull { it.id == ownedId }
        ?: infos.singleOrNull()?.takeIf { recover }

    /** Called only under workLock, for the selected real work owner. */
    private fun publishWorkInfo(info: WorkInfo) {
        ownedWorkId = info.id
        ownedWorkInfo = info
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                _inFlight.value = DownloadProgress(DownloadProgress.Phase.QUEUED, 0)
                if (_downloadError.value != DOWNLOAD_CANCEL_FAILED) _downloadError.value = null
            }
            WorkInfo.State.RUNNING -> {
                val bytes = info.progress.getLong("bytes", 0)
                val total = info.progress.getLong("total", -1)
                val parsed = runCatching {
                    DownloadProgress.Phase.valueOf(info.progress.getString("phase") ?: "DOWNLOADING")
                }.getOrNull()
                // QUEUED is a native work state, never a running worker phase.
                val phase = parsed?.takeUnless { it == DownloadProgress.Phase.QUEUED }
                    ?: DownloadProgress.Phase.DOWNLOADING.also {
                        recordWorkFailure(WorkSeam.OBSERVER, WorkFailureClass.UNKNOWN_PHASE, workGeneration)
                    }
                _inFlight.value = DownloadProgress(phase, bytes, total.takeIf { it > 0 })
                if (_downloadError.value != DOWNLOAD_CANCEL_FAILED) _downloadError.value = null
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> {
                _inFlight.value = null
                _downloadError.value = null
            }
            WorkInfo.State.FAILED -> {
                _inFlight.value = null
                _downloadError.value = info.outputData.getString(MapPackDownloadWorker.KEY_RESULT_ERROR)
                    ?.takeIf { it.isNotBlank() } ?: DOWNLOAD_ERROR_FALLBACK
                recordWorkFailure(WorkSeam.OBSERVER, WorkFailureClass.WORK_FAILED, workGeneration)
            }
        }
    }

    @Serializable
    private enum class WorkSeam { OBSERVER, ENQUEUE, CANCEL }

    @Serializable
    private enum class WorkFailureClass {
        OPERATION_REJECTED, UNKNOWN_PHASE, WORK_FAILED, AMBIGUOUS_WORK, OBSERVER_FAILED,
        HEALTH_READ_FAILED, HEALTH_WRITE_FAILED,
    }

    @Serializable
    private data class WorkFailureHealth(
        val schema: Int,
        val component: String,
        val seam: WorkSeam,
        @SerialName("class") val failureClass: WorkFailureClass,
        val failureCount: Long,
        val lastFailureAtMillis: Long,
        val generation: Long,
    )

    @Suppress("TooGenericExceptionCaught") // WorkManager can reject synchronously or in its operation result.
    private fun runWorkOperation(seam: WorkSeam, generation: Long, requestId: UUID? = null, operation: () -> Operation) {
        try {
            val result = operation().result
            result.addListener(
                {
                    if (runCatching { result.get() }.isFailure) {
                        workOperationFailed(seam, generation, requestId)
                    } else if (seam == WorkSeam.CANCEL) {
                        synchronized(workLock) {
                            if (workGeneration == generation) pendingCancel = null
                        }
                    }
                },
                Executor { task -> scope.launch { task.run() } },
            )
        } catch (_: Exception) {
            workOperationFailed(seam, generation, requestId)
        }
    }

    private fun workOperationFailed(seam: WorkSeam, generation: Long, requestId: UUID?) {
        synchronized(workLock) {
            if (workGeneration == generation) {
                if (seam == WorkSeam.ENQUEUE && requestedWorkId == requestId) {
                    // Rejection is not proof that any already-observed work stopped.
                    if (ownedWorkId == null) _inFlight.value = null
                    _downloadError.value = DOWNLOAD_ERROR_FALLBACK
                } else if (seam == WorkSeam.CANCEL) {
                    pendingCancel?.takeIf { it.generation == generation }?.let { cancelled ->
                        pendingCancel = null
                        ownedWorkId = cancelled.ownedId
                        ownedWorkInfo = cancelled.latest
                        requestedWorkId = cancelled.requestedId
                        recoverUnownedWork = cancelled.recoverUnowned
                        _inFlight.value = cancelled.progress
                        _downloadError.value = DOWNLOAD_CANCEL_FAILED
                        cancelled.latest?.let(::publishWorkInfo)
                    }
                }
            }
        }
        // Publish after the UI repair, so a health readback also fences that callback.
        recordWorkFailure(seam, WorkFailureClass.OPERATION_REJECTED, generation)
    }

    private fun logWorkFailure(seam: String, failureClass: WorkFailureClass, generation: Long) {
        if (emittedFailures.computeIfAbsent("$seam/${failureClass.name}") { AtomicInteger() }.getAndIncrement() >= 5) return
        runCatching {
            android.util.Log.e(
                "MapPackManager",
                "schema=1 component=map-pack seam=$seam class=${failureClass.name} generation=$generation",
            )
        }
    }

    private fun recordWorkFailure(seam: WorkSeam, failureClass: WorkFailureClass, generation: Long) {
        logWorkFailure(seam.name, failureClass, generation)
        // Three bounded device-only rows, not a queue/job store. Serialize the
        // read-increment-replace and atomically publish; never persist private payload.
        scope.launch {
            synchronized(healthLock) {
                val file = runCatching {
                    val directory = context.noBackupFilesDir
                    check(directory.isDirectory || directory.mkdirs())
                    File(directory, "pack-download-${seam.name}.failed")
                }.getOrElse {
                    logWorkFailure("health-write", WorkFailureClass.HEALTH_WRITE_FAILED, generation)
                    return@launch
                }
                val previous = runCatching {
                    if (!file.exists() || (file.isFile && file.length() == 0L)) null // Legacy empty marker.
                    else {
                        check(file.isFile && file.length() <= 1024L)
                        healthJson.decodeFromString<WorkFailureHealth>(file.readText()).also {
                            check(it.schema == 1 && it.component == "map-pack" && it.seam == seam)
                            check(it.failureCount > 0 && it.lastFailureAtMillis > 0 && it.generation >= 0)
                        }
                    }
                }.getOrElse {
                    // Preserve an unreadable existing row; do not reset its counter.
                    logWorkFailure("health-read", WorkFailureClass.HEALTH_READ_FAILED, generation)
                    return@launch
                }
                val row = WorkFailureHealth(
                    1, "map-pack", seam, failureClass,
                    previous?.failureCount?.coerceAtMost(Long.MAX_VALUE - 1)?.plus(1) ?: 1,
                    System.currentTimeMillis(), generation,
                )
                runCatching {
                    val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
                    try {
                        FileOutputStream(temporary).use { output ->
                            output.write(healthJson.encodeToString(row).toByteArray(Charsets.UTF_8))
                            output.fd.sync()
                        }
                        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        if (temporary.exists()) check(temporary.delete())
                    }
                }.onFailure {
                    logWorkFailure("health-write", WorkFailureClass.HEALTH_WRITE_FAILED, generation)
                }
            }
        }
    }

    /**
     * The download-success observer's tail: atomically adopt-or-suppress whatever
     * landed in current.json. Internal so unit tests can drive the exact observer
     * behaviour against [stateMutex]-guarded deletes.
     */
    internal suspend fun adoptRestoredPack() {
        stateMutex.withLock {
            val installedNow = packStateStore.readCurrent()
            if (installedNow != null && installedNow.version in suppressedAfterDelete) {
                // Racing worker completed after an uninstall and rewrote persisted
                // state; drop the resurrected DIRECTORY too, not just current.json,
                // so the suppressed version cannot lie orphaned on disk.
                packStateStore.clearCurrent()
                withContext(Dispatchers.IO) {
                    packStateStore.packDir(installedNow.version).deleteRecursively()
                    packStateStore.cleanupIfEmpty()
                }
                _installed.value = null
            } else {
                _installed.value = installedNow
            }
        }
    }

    fun currentPackDir(): java.io.File? {
        val version = _installed.value?.version ?: return null
        if (version.isEmpty()) return null
        return packStateStore.packDir(version)
    }

    /**
     * Deletes the installed pack's directory and clears its persisted state so it
     * does not resurrect after restart; the [installed] flow reflects the removal
     * immediately. Returns true when a pack directory was actually deleted.
     */
    suspend fun deleteInstalled(): Boolean {
        val current = _installed.value ?: return false
        // Request cancellation, not proof work stopped. The shared-lock tombstone
        // below still suppresses a racing worker completion even if Cancel is rejected.
        cancelDownload()
        val dir = packStateStore.packDir(current.version)
        val deleted = stateMutex.withLock {
            // Tombstone add + reset are atomic against adoptRestoredPack(): an
            // observer that already read stale disk still lands AFTER this block
            // and re-checks the set under the same lock before publishing.
            suppressedAfterDelete.add(current.version)
            val didDelete = withContext(Dispatchers.IO) { dir.exists() && dir.deleteRecursively() }
            packStateStore.clearCurrent()
            packStateStore.writePrevious(null)
            _installed.value = null
            packStateStore.cleanupIfEmpty()
            didDelete
        }
        return deleted
    }
}
