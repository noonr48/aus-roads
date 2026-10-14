package au.com.ausroads.offline.download.state

/** Closed, payload-free action failure; recover with Cancel, never download Retry. */
const val DOWNLOAD_CANCEL_FAILED = "cancel_failed"

data class DownloadProgress(
    val phase: Phase,
    val bytesDownloaded: Long,
    val totalBytes: Long? = null,
    val percent: Int? = totalBytes?.let { if (it > 0) ((bytesDownloaded * 100) / it).toInt() else null },
) {
    enum class Phase {
        QUEUED,
        FETCHING_MANIFEST,
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        INSTALLING,
    }
}
