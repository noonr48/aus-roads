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
import au.com.ausroads.offline.pack.PackManifest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lenient JSON matching ManifestFetcher's, so a manifest accepted at fetch time
 * (e.g. with unknown keys) also decodes here — otherwise it would decode to null
 * and verification would be silently skipped.
 */
private val MANIFEST_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

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
            val partialDir = packStateStore.partialDir()
            partialDir.mkdirs()
            val zipFile = File(partialDir, "pack.zip")

            packDownloader.download(
                url = packUrl,
                target = zipFile,
                onProgress = { bytes, total ->
                    setProgressAsync(Data.Builder()
                        .putLong("bytes", bytes)
                        .putLong("total", total ?: -1)
                        .putString("phase", DownloadProgress.Phase.DOWNLOADING.name)
                        .build())
                }
            )

            // 2. Extract
            val installDir = packStateStore.packDir(packVersion)
            setProgressAsync(Data.Builder()
                .putString("phase", DownloadProgress.Phase.EXTRACTING.name)
                .build())
            packExtractor.extract(zipFile, installDir)

            // 3 + 4. Verify (if manifest available) then install. Delegated to
            // PackInstaller so the verify-fail cleanup + current.json write are
            // unit-testable without WorkManager/foreground machinery.
            setProgressAsync(Data.Builder()
                .putString("phase", DownloadProgress.Phase.VERIFYING.name)
                .build())
            val manifest = manifestJson?.let {
                try { MANIFEST_JSON.decodeFromString(PackManifest.serializer(), it) } catch (_: Exception) { null }
            }
            setProgressAsync(Data.Builder()
                .putString("phase", DownloadProgress.Phase.INSTALLING.name)
                .build())
            val outcome = packInstaller.finalize(
                installDir = installDir,
                packVersion = packVersion,
                manifest = manifest,
                manifestJson = manifestJson,
                zipLength = zipFile.length(),
            )
            if (outcome is PackInstaller.Outcome.VerificationFailed) {
                // A rejected pack must not survive as scratch state: the next attempt
                // would Range-resume onto the poisoned .partial/pack.zip bytes and fail
                // verification forever.
                deletePartialScratch(partialDir)
                return Result.failure(
                    Data.Builder().putString(KEY_RESULT_ERROR, outcome.message).build()
                )
            }

            // 5. Cleanup
            zipFile.delete()
            partialDir.deleteRecursively()

            Result.success()
        } catch (t: Throwable) {
            // May rethrow: cancellation is control flow, never a download failure.
            val errorMessage = workerErrorMessageOrRethrow(t)
            // A download/extract failure may have created a half-written v<version>
            // dir; remove it and the base dir if no installed pack remains so a
            // failed attempt leaves no confusing empty directory behind. The scratch
            // zip goes too: cleanupIfEmpty only reaches it when nothing remains
            // installed, so poison must be dropped explicitly on every failure path.
            runCatching {
                packStateStore.packDir(packVersion).deleteRecursively()
                deletePartialScratch(packStateStore.partialDir())
                packStateStore.cleanupIfEmpty()
            }
            Result.failure(Data.Builder().putString(KEY_RESULT_ERROR, errorMessage).build())
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
 * Called on the verification-failure exit and on every generic-failure exit
 * (the catch-all); the SUCCESS exit performs the equivalent removal inline
 * right after moving the zip into place. Cancellation deliberately skips it:
 * the partial bytes survive so the next attempt can Range-resume from them,
 * which is self-healing — an unsound resume fails SHA verification, and the
 * verification-failure path above deletes the poisoned scratch instead of
 * Range-resuming onto it forever.
 */
internal fun deletePartialScratch(scratchDir: File) {
    scratchDir.deleteRecursively()
}
