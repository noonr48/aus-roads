package au.com.ausroads.offline.download.state

data class DownloadProgress(
    val phase: Phase,
    val bytesDownloaded: Long,
    val totalBytes: Long? = null,
    val percent: Int? = totalBytes?.let { if (it > 0) ((bytesDownloaded * 100) / it).toInt() else null },
) {
    enum class Phase {
        FETCHING_MANIFEST,
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        INSTALLING,
    }
}
