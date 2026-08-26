package au.com.ausroads.offline.download

import android.content.Context
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.manifest.ManifestFetcher
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    // A crashed observer coroutine must not take the process down: log it and
    // keep the last published _installed value instead.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e(
                "MapPackManager",
                "map-pack state coroutine failed; keeping last published installed state",
                throwable,
            )
        },
    )
    private val workManager = WorkManager.getInstance(context)

    private val _installed = MutableStateFlow<InstalledPack?>(null)
    val installed: StateFlow<InstalledPack?> = _installed.asStateFlow()

    private val _inFlight = MutableStateFlow<DownloadProgress?>(null)
    val inFlight: StateFlow<DownloadProgress?> = _inFlight.asStateFlow()

    /**
     * The error message from the most recent FAILED download worker run, or null
     * when no download has failed (or a new download started). Surfaced so the UI
     * can show verify/download failures instead of them disappearing silently.
     */
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

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
            _installed.value = packStateStore.readCurrent()
        }

        // Observe WorkManager for in-flight downloads
        scope.launch {
            workManager.getWorkInfosForUniqueWorkLiveData("pack-download")
                .asFlow()
                .mapNotNull { it.firstOrNull() }
                .collect { info ->
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val bytes = info.progress.getLong("bytes", 0)
                            val total = info.progress.getLong("total", -1)
                            val phaseName = info.progress.getString("phase") ?: "DOWNLOADING"
                            // Never let an unexpected phase string crash the observer
                            // coroutine (valueOf throws on an unknown name).
                            val phase = runCatching { DownloadProgress.Phase.valueOf(phaseName) }
                                .getOrDefault(DownloadProgress.Phase.DOWNLOADING)
                            _inFlight.value = DownloadProgress(
                                phase = phase,
                                bytesDownloaded = bytes,
                                totalBytes = if (total > 0) total else null,
                            )
                            _downloadError.value = null
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _inFlight.value = null
                            _downloadError.value = null
                            adoptRestoredPack()
                        }
                        WorkInfo.State.FAILED -> {
                            _inFlight.value = null
                            // Surface the worker's error so verify/download failures
                            // are no longer invisible to the user.
                            _downloadError.value =
                                info.outputData.getString(MapPackDownloadWorker.KEY_RESULT_ERROR)
                        }
                        else -> {}
                    }
                }
        }
    }

    suspend fun fetchLatestManifest(): ManifestFetchResult = manifestFetcher.fetch()

    fun startDownload(packUrl: String, packVersion: String, manifestJson: String? = null) {
        // Clear any stale failure from a previous attempt before re-enqueuing.
        _downloadError.value = null
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

        workManager.enqueueUniqueWork(
            "pack-download",
            androidx.work.ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelDownload() {
        workManager.cancelUniqueWork("pack-download")
        _inFlight.value = null
        _downloadError.value = null
    }

    /** Dismiss the surfaced worker error (e.g. after the user has seen it). */
    fun clearDownloadError() {
        _downloadError.value = null
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
        // Kill any in-flight download first: a worker finishing after we cleared
        // state would otherwise resurrect the pack via the SUCCEEDED observer.
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
