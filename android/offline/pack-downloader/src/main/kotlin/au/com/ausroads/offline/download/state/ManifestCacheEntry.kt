package au.com.ausroads.offline.download.state

import kotlinx.serialization.Serializable

@Serializable
data class ManifestCacheEntry(
    val etag: String? = null,
    val lastModified: String? = null,
    val manifestJson: String,
)
