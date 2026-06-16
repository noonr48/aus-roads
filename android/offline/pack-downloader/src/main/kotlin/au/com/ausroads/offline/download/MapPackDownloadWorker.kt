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
import kotlinx.serialization.json.Json
import java.io.File

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

    override suspend fun doWork(): Result {
        val packUrl = inputData.getString(KEY_PACK_URL) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT_ERROR, applicationContext.getString(R.string.download_missing_url)).build()
        )
        val packVersion = inputData.getString(KEY_PACK_VERSION) ?: return Result.failure(
            Data.Builder().putString(KEY_RESULT_ERROR, applicationContext.getString(R.string.download_missing_version)).build()
        )
        val manifestJson = inputData.getString(KEY_MANIFEST_JSON)

        setForeground(createForegroundInfo())

        return try {
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
                try { Json.decodeFromString(PackManifest.serializer(), it) } catch (_: Exception) { null }
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
                return Result.failure(
                    Data.Builder().putString(KEY_RESULT_ERROR, outcome.message).build()
                )
            }

            // 5. Cleanup
            zipFile.delete()
            partialDir.deleteRecursively()

            Result.success()
        } catch (e: Exception) {
            // A download/extract failure may have created a half-written v<version>
            // dir; remove it and the base dir if no installed pack remains so a
            // failed attempt leaves no confusing empty directory behind.
            runCatching {
                packStateStore.packDir(packVersion).deleteRecursively()
                packStateStore.cleanupIfEmpty()
            }
            Result.failure(Data.Builder().putString(KEY_RESULT_ERROR, e.message).build())
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
