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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapPackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manifestFetcher: ManifestFetcher,
    private val packStateStore: PackStateStore,
    private val evictionManager: EvictionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                            _installed.value = packStateStore.readCurrent()
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

    fun currentPackDir(): java.io.File? {
        val version = _installed.value?.version ?: return null
        if (version.isEmpty()) return null
        return packStateStore.packDir(version)
    }
}
