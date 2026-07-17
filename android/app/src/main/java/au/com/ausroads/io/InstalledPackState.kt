package au.com.ausroads.io

import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.hasSafeStorageIdentity
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val installedPackJson = Json { ignoreUnknownKeys = true }

/**
 * Decode the downloader's canonical current-pack sentinel.
 *
 * Map, search, and routing all call this reader so malformed or incomplete
 * metadata cannot make different consumers select different pack generations.
 */
internal fun readCurrentInstalledPack(filesDir: File): InstalledPack? {
    val current = File(filesDir, "mappacks/au-sa/current.json")
    if (!current.isFile) return null
    return runCatching {
        installedPackJson.decodeFromString<InstalledPack>(current.readText())
    }.getOrNull()?.takeIf(InstalledPack::hasSafeStorageIdentity)
}
