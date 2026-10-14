package au.com.ausroads.offline.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import au.com.ausroads.offline.downloader.R
import au.com.ausroads.offline.download.download.PackDownloader
import au.com.ausroads.offline.download.download.PackExtractor
import au.com.ausroads.offline.download.download.PackInstaller
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.PackStateStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.util.Comparator

@HiltWorker
class MapPackDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val packDownloader: PackDownloader,
    private val packExtractor: PackExtractor,
    private val packStateStore: PackStateStore,
    private val packInstaller: PackInstaller,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_PACK_URL = "pack_url"
        const val KEY_PACK_VERSION = "pack_version"
        const val KEY_MANIFEST_JSON = "manifest_json"
        const val CHANNEL_ID = "pack_download"
        const val NOTIFICATION_ID = 1

        const val KEY_RESULT_ERROR = "result_error"
    }

    // Catches Throwable (not just Exception) so OOM-class Errors still surface a
    // user-visible failure; CancellationException is rethrown inside instead.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val packUrl = inputData.getString(KEY_PACK_URL) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT_ERROR, applicationContext.getString(R.string.download_missing_url)).build()
        )
        val packVersion = inputData.getString(KEY_PACK_VERSION) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT_ERROR, applicationContext.getString(R.string.download_missing_version)).build()
        )
        val manifestJson = inputData.getString(KEY_MANIFEST_JSON)

        return try {
            // Foreground promotion runs INSIDE the guarded region: an FGS-start
            // denial (e.g. ForegroundServiceStartNotAllowedException on Android
            // 12+) then flows through workerErrorMessageOrRethrow below into a
            // normal KEY_RESULT_ERROR failure instead of crashing the worker
            // coroutine before WorkManager sees a Result.
            setForeground(createForegroundInfo())

            // 1. Download
            val zipFile = downloadPackToScratch(
                url = packUrl,
                packDownloader = packDownloader,
                packStateStore = packStateStore,
                onProgress = { bytes, total ->
                    setProgressAsync(Data.Builder()
                        .putLong("bytes", bytes)
                        .putLong("total", total ?: -1)
                        .putString("phase", DownloadProgress.Phase.DOWNLOADING.name)
                        .build())
                }
            )

            // 2–5. The real extraction/installer boundary owns the shared state
            // transaction, including rejection and late-failure cleanup.
            val outcome = installDownloadedPack(
                zipFile = zipFile,
                packVersion = packVersion,
                manifestJson = manifestJson,
                packExtractor = packExtractor,
                packStateStore = packStateStore,
                packInstaller = packInstaller,
                onPhase = { phase ->
                    setProgressAsync(Data.Builder().putString("phase", phase.name).build())
                },
            )
            when (outcome) {
                PackInstaller.Outcome.Installed -> Result.success()
                is PackInstaller.Outcome.VerificationFailed -> Result.failure(workerFailureData(outcome.message))
            }
        } catch (t: Throwable) {
            Result.failure(workerFailureData(
                cleanupDownloadFailure(t, packVersion, packStateStore, packInstaller),
            ))
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(applicationContext.getString(R.string.download_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Map pack downloads",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

/** Real worker download boundary: Store validation must precede the downloader's first write. */
internal suspend fun downloadPackToScratch(
    url: String,
    packDownloader: PackDownloader,
    packStateStore: PackStateStore,
    onProgress: (Long, Long?) -> Unit = { _, _ -> },
): File = packDownloader.download(url, File(packStateStore.partialDir(), "pack.zip"), onProgress)

/** Smallest real worker tail: real ZIP/extractor/installer, without foreground machinery. */
internal suspend fun installDownloadedPack(
    zipFile: File,
    packVersion: String,
    manifestJson: String?,
    packExtractor: PackExtractor,
    packStateStore: PackStateStore,
    packInstaller: PackInstaller,
    onPhase: suspend (DownloadProgress.Phase) -> Unit = {},
): PackInstaller.Outcome {
    return try {
        packStateStore.partialDir() // Refuse unsafe scratch before extraction/adoption, not only cleanup.
        val outcome = packInstaller.extractAndFinalize(zipFile, packVersion, manifestJson, packExtractor) { phase ->
            onPhase(phase)
            packStateStore.partialDir() // Recheck after a suspension before the next installer IO phase.
        }
        deletePartialScratch(packStateStore.partialDir())
        if (outcome is PackInstaller.Outcome.VerificationFailed) {
            packInstaller.cleanupFailedAttempt(packVersion)
        }
        outcome
    } catch (t: Throwable) {
        PackInstaller.Outcome.VerificationFailed(
            cleanupDownloadFailure(t, packVersion, packStateStore, packInstaller),
        )
    }
}

/** The actual doWork and tail catch share this order; unsafe scratch never escalates cleanup. */
internal suspend fun cleanupDownloadFailure(
    failure: Throwable,
    packVersion: String,
    packStateStore: PackStateStore,
    packInstaller: PackInstaller,
): String {
    // Cancellation escapes before validation/deletion so Range-resume bytes survive.
    val message = workerErrorMessageOrRethrow(failure)
    if (message == PackStateStore.SCRATCH_INVALID) return message
    val cleanupFailure = runCatching {
        deletePartialScratch(packStateStore.partialDir())
        packInstaller.cleanupFailedAttempt(packVersion)
    }.exceptionOrNull()
    return if (cleanupFailure?.message == PackStateStore.SCRATCH_INVALID) PackStateStore.SCRATCH_INVALID else message
}

/** The same fixed/error payload is stored in WorkManager output and observed by the manager. */
internal fun workerFailureData(message: String): Data = Data.Builder()
    .putString(MapPackDownloadWorker.KEY_RESULT_ERROR, message)
    .build()

/** Fallback KEY_RESULT_ERROR payload for throwables carrying no usable message. */
internal const val DOWNLOAD_ERROR_FALLBACK = "download failed"

/**
 * Maps an escaped pipeline throwable to its KEY_RESULT_ERROR payload.
 *
 * CancellationException (incl. WorkManager's JobCancellationException subclass) is
 * cooperative-cancel CONTROL FLOW and is RETHROWN — treating it as a failure would
 * mark ordinary cancelled stops FAILED with bogus errors. Every other Throwable,
 * INCLUDING Error-class ones such as OutOfMemoryError (previously swallowed into a
 * silent FAILED state by an Exception-only catch), maps to a non-blank message:
 * the throwable's own message when present, else [DOWNLOAD_ERROR_FALLBACK].
 */
internal fun workerErrorMessageOrRethrow(t: Throwable): String {
    if (t is CancellationException) throw t
    return t.message?.takeIf { it.isNotBlank() } ?: DOWNLOAD_ERROR_FALLBACK
}

/**
 * Deletes the .partial scratch tree holding a half-downloaded pack.zip.
 *
 * Called on safe verification/generic-failure exits and success through
 * [installDownloadedPack]. An unsafe identity is refusal, never cleanup permission.
 * Cancellation deliberately skips it:
 * the partial bytes survive so the next attempt can Range-resume from them,
 * which is self-healing — an unsound resume fails SHA verification, and the
 * verification-failure path above deletes the poisoned scratch instead of
 * Range-resuming onto it forever.
 */
internal fun deletePartialScratch(scratchDir: File) {
    // Production callers obtain the contained identity from Store immediately
    // before this preflight. Reject the entire linked/unknown tree before deleting
    // anything, and never use link-following File.deleteRecursively().
    val entries = requireNotNull(PackStateStore.unlinkedTree(scratchDir)) { PackStateStore.SCRATCH_INVALID }
    entries.sortedWith(Comparator.reverseOrder()).forEach { Files.delete(it) }
}
