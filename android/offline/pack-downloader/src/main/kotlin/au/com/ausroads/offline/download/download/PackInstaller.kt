/*
 * PackInstaller — the guarded extract → verify → install tail of a map-pack download.
 *
 * Uses eviction's existing state transaction so an adopted current/previous target
 * cannot be overwritten or removed by a retained Retry, including failure cleanup.
 * The worker keeps download/foreground concerns; this real boundary is JVM-testable.
 */
package au.com.ausroads.offline.download.download

import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.state.DownloadProgress.Phase
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.offline.pack.PackManifest
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import javax.inject.Inject

// Match ManifestFetcher: unknown fields are supported, undecodable supplied JSON is not.
private val MANIFEST_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

class PackInstaller @Inject constructor(
    private val packVerifier: PackVerifier,
    private val packStateStore: PackStateStore,
    private val evictionManager: EvictionManager,
    private val clock: Clock = Clock.System,
) {

    companion object {
        // Persisted by the worker in KEY_RESULT_ERROR; never include version/path/JSON bytes.
        internal const val TARGET_REFERENCED = "pack_target_referenced"
        internal const val MANIFEST_INVALID = "pack_manifest_invalid"
        internal const val STATE_UNREADABLE = "pack_state_unreadable"
        internal const val TARGET_INVALID = PackStateStore.TARGET_INVALID
    }

    sealed interface Outcome {
        data object Installed : Outcome
        /** Verification/refusal failure. Referenced install bytes are never removed. */
        data class VerificationFailed(val message: String) : Outcome
    }

    /** Shares the eviction/manager state transaction throughout actual extraction and adoption. */
    internal suspend fun extractAndFinalize(
        zipFile: File,
        packVersion: String,
        manifestJson: String?,
        packExtractor: PackExtractor,
        onPhase: suspend (Phase) -> Unit = {},
    ): Outcome = evictionManager.withStateLock {
        val installDir = packStateStore.installTarget(packVersion)
            ?: return@withStateLock Outcome.VerificationFailed(TARGET_INVALID)
        targetRefusalLocked(installDir, packVersion)?.let { return@withStateLock Outcome.VerificationFailed(it) }
        val manifest = try {
            manifestJson?.let { MANIFEST_JSON.decodeFromString(PackManifest.serializer(), it) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return@withStateLock Outcome.VerificationFailed(MANIFEST_INVALID)
        }
        onPhase(Phase.EXTRACTING)
        packExtractor.extract(zipFile, installDir)
        onPhase(Phase.VERIFYING)
        finalizeLocked(installDir, packVersion, manifest, manifestJson, zipFile.length()) {
            onPhase(Phase.INSTALLING)
        }
    }

    /** Verify and publish under the same lock even for callers that already extracted. */
    suspend fun finalize(
        installDir: File,
        packVersion: String,
        manifest: PackManifest?,
        manifestJson: String?,
        zipLength: Long,
    ): Outcome = evictionManager.withStateLock {
        finalizeLocked(installDir, packVersion, manifest, manifestJson, zipLength)
    }

    /** Re-read live references, not a cached pre-download decision, before late cleanup. */
    internal suspend fun cleanupFailedAttempt(packVersion: String) = evictionManager.withStateLock {
        val installDir = packStateStore.installTarget(packVersion) ?: return@withStateLock
        removeUnreferencedInstallLocked(installDir, packVersion)
    }

    private suspend fun finalizeLocked(
        installDir: File,
        packVersion: String,
        manifest: PackManifest?,
        manifestJson: String?,
        zipLength: Long,
        onInstalling: suspend () -> Unit = {},
    ): Outcome {
        targetRefusalLocked(installDir, packVersion)?.let { return Outcome.VerificationFailed(it) }
        if (!installDir.isDirectory) return Outcome.VerificationFailed(TARGET_INVALID)
        // null/null is the deliberately supported legacy install, not a failed JSON decode.
        if (manifestJson != null && manifest == null) {
            removeUnreferencedInstallLocked(installDir, packVersion)
            return Outcome.VerificationFailed(MANIFEST_INVALID)
        }
        if (manifest != null) {
            when (val result = packVerifier.verify(installDir, manifest)) {
                is PackVerifier.VerificationResult.Ok -> { /* pass */ }
                is PackVerifier.VerificationResult.Mismatch -> {
                    removeUnreferencedInstallLocked(installDir, packVersion)
                    return Outcome.VerificationFailed(
                        "Verification failed: ${result.component} " +
                            "(expected=${result.expected}, actual=${result.actual})",
                    )
                }
            }
        }

        onInstalling()
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
        // Already in eviction's shared transaction; its ordinary entrypoint re-locks.
        evictionManager.onNewInstallLocked(installedPack)
        return Outcome.Installed
    }

    private suspend fun targetRefusalLocked(targetDir: File, packVersion: String): String? {
        val expected = packStateStore.installTarget(packVersion) ?: return TARGET_INVALID
        if (targetDir.absoluteFile != expected.absoluteFile) return TARGET_INVALID
        val baseDir = packStateStore.packDir("").parentFile ?: return TARGET_INVALID
        val current = packStateStore.readCurrent()
        val previous = packStateStore.readPrevious()
        // Null also covers malformed versions and symlink metadata/references;
        // NOFOLLOW distinguishes dangling metadata links from true absence.
        if ((current == null && Files.exists(File(baseDir, "current.json").toPath(), NOFOLLOW_LINKS)) ||
            (previous == null && Files.exists(File(baseDir, "previous.json").toPath(), NOFOLLOW_LINKS))) return STATE_UNREADABLE
        return if (listOfNotNull(current, previous).any { it.version == packVersion }) TARGET_REFERENCED else null
    }

    private suspend fun removeUnreferencedInstallLocked(installDir: File, packVersion: String) {
        if (targetRefusalLocked(installDir, packVersion) != null) return
        installDir.deleteRecursively()
        packStateStore.cleanupIfEmpty()
    }

    private fun sha256OfJson(json: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
