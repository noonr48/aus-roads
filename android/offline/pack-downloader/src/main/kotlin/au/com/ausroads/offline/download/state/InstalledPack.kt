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
    /** Physical generation directory; null preserves the legacy v<version> layout. */
    val directoryName: String? = null,
)

private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._-]+")

/** Reject descriptor-controlled path traversal before resolving component files. */
fun InstalledPack.hasSafeStorageIdentity(): Boolean =
    version.matches(SAFE_PATH_SEGMENT) &&
        (directoryName == null || directoryName.matches(SAFE_PATH_SEGMENT))

/** New installs carry a unique directory; old descriptors remain readable in v<version>. */
fun InstalledPack.installDirectoryName(): String =
    (directoryName ?: "v$version").also {
        require(hasSafeStorageIdentity()) { "Unsafe installed-pack storage identity" }
    }
