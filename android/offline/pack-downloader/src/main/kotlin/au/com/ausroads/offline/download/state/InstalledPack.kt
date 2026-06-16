package au.com.ausroads.offline.download.state

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class InstalledPack(
    val version: String,
    val regionCode: String,
    val installedAt: Instant,
    val totalSizeBytes: Long,
    val tilesPath: String,
    val searchPath: String? = null,
    val routingPath: String? = null,
    val manifestSha256: String,
)
