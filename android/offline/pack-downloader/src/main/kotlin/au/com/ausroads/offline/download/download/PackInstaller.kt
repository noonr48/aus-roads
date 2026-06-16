/*
 * PackInstaller — the verify → install → cleanup tail of a map-pack download.
 *
 * Extracted from MapPackDownloadWorker so this decision logic (the part that
 * matters for correctness: a verify failure must delete the half-written install
 * and not leave an empty mappacks/au-sa dir; a success must write current.json)
 * is unit-testable without WorkManager/foreground/Robolectric machinery. The
 * worker keeps the IO + progress/foreground concerns and delegates here.
 */
package au.com.ausroads.offline.download.download

import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.offline.pack.PackManifest
import kotlinx.datetime.Clock
import java.io.File
import javax.inject.Inject

class PackInstaller @Inject constructor(
    private val packVerifier: PackVerifier,
    private val packStateStore: PackStateStore,
    private val evictionManager: EvictionManager,
    private val clock: Clock = Clock.System,
) {

    sealed interface Outcome {
        data object Installed : Outcome
        /** Verification failed; the install dir was removed. [message] is user-facing-ish. */
        data class VerificationFailed(val message: String) : Outcome
    }

    /**
     * Verify [installDir] against [manifest] (when present) and, on success, record
     * it as the current pack. On verify failure the install dir is deleted and the
     * region base dir is removed if no installed pack remains.
     *
     * @param zipLength fallback total size when the manifest doesn't declare one.
     */
    suspend fun finalize(
        installDir: File,
        packVersion: String,
        manifest: PackManifest?,
        manifestJson: String?,
        zipLength: Long,
    ): Outcome {
        if (manifest != null) {
            when (val result = packVerifier.verify(installDir, manifest)) {
                is PackVerifier.VerificationResult.Ok -> { /* pass */ }
                is PackVerifier.VerificationResult.Mismatch -> {
                    installDir.deleteRecursively()
                    // Don't leave an empty mappacks/au-sa behind on a failed download.
                    packStateStore.cleanupIfEmpty()
                    return Outcome.VerificationFailed(
                        "Verification failed: ${result.component} " +
                            "(expected=${result.expected}, actual=${result.actual})",
                    )
                }
            }
        }

        val regionCode = manifest?.let { "${it.region.country}-${it.region.state}" } ?: "AU-SA"
        val installedPack = InstalledPack(
            version = packVersion,
            regionCode = regionCode,
            installedAt = clock.now(),
            totalSizeBytes = manifest?.totalSizeBytes ?: zipLength,
            tilesPath = manifest?.components?.tiles?.path ?: "tiles.mbtiles",
            searchPath = manifest?.components?.search?.takeIf { sc -> sc.format != "none" }?.path ?: "search.db",
            routingPath = manifest?.components?.routing?.takeIf { rc -> rc.format != "none" }?.path,
            manifestSha256 = manifestJson?.let { sha256OfJson(it) } ?: "",
        )
        evictionManager.onNewInstall(installedPack)
        return Outcome.Installed
    }

    private fun sha256OfJson(json: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
