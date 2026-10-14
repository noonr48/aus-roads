package au.com.ausroads.offline.download.eviction

import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Comparator

private const val EVICTION_TARGET_INVALID = "component=map-pack-state seam=eviction-preflight class=target-invalid"
private const val EVICTION_TREE_UNREADABLE = "component=map-pack-state seam=eviction-preflight class=tree-unreadable"
private const val EVICTION_DELETE_FAILED = "component=map-pack-state seam=stale-delete class=io-failed"

/**
 * @param stateMutex defaults to this instance's own lock so standalone
 *   constructions (unit tests) stay self-consistent. Production wiring injects
 *   the MapPackManager-shared singleton mutex so eviction's current.json and
 *   previous.json writes serialize against the delete/suppression protocol
 *   instead of interleaving with it.
 */
class EvictionManager(
    private val packStateStore: PackStateStore,
    private val stateMutex: Mutex = Mutex(),
) {

    /**
     * On new install: promote current → previous, write new current, delete stale dirs.
     */
    suspend fun onNewInstall(newPack: InstalledPack) = withStateLock {
        onNewInstallLocked(newPack)
    }

    /** Reuse the production manager/eviction mutex, including destructive IO. */
    internal suspend fun <T> withStateLock(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            stateMutex.withLock { block() }
        }

    /** Caller already holds the shared transaction; do not acquire it again. */
    internal suspend fun onNewInstallLocked(newPack: InstalledPack) {
        checkNotNull(packStateStore.installTarget(newPack.version)) { EVICTION_TARGET_INVALID }
        val (current, previous) = readReferencesLocked()
        val staleDirs = preflightStaleDirs(setOf(newPack.version) + listOfNotNull((current ?: previous)?.version))
        if (current != null) packStateStore.writePrevious(current)
        packStateStore.writeCurrent(newPack)
        deleteStaleDirs(staleDirs)
    }

    /**
     * On app launch: validate current.json references an existing dir.
     * If not, revert to previous.json. Delete unreferenced v* dirs.
     */
    suspend fun reconcile() = withStateLock {
        val (current, previous) = readReferencesLocked()

        val currentDir = current?.let {
            checkNotNull(packStateStore.installTarget(it.version)) { EVICTION_TARGET_INVALID }
        }
        val previousDir = previous?.let {
            checkNotNull(packStateStore.installTarget(it.version)) { EVICTION_TARGET_INVALID }
        }
        val currentValid = currentDir != null && Files.isDirectory(currentDir.toPath(), NOFOLLOW_LINKS)
        val previousValid = previousDir != null && Files.isDirectory(previousDir.toPath(), NOFOLLOW_LINKS)
        val revert = !currentValid && current != null
        val keepVersions = if (revert) {
            if (previousValid) setOf(previous!!.version) else emptySet()
        } else listOfNotNull(current?.version, previous?.version).toSet()
        // Validate the ENTIRE cleanup set before even fallback/clear publication.
        val staleDirs = preflightStaleDirs(keepVersions)

        if (revert) {
            if (previousValid) packStateStore.writeCurrent(previous!!) else packStateStore.clearCurrent()
            packStateStore.writePrevious(null)
        }
        deleteStaleDirs(staleDirs)
    }

    /** Missing metadata is supported; undecodable existing metadata is not deletion authority. */
    private suspend fun readReferencesLocked(): Pair<InstalledPack?, InstalledPack?> {
        val current = packStateStore.readCurrent()
        val previous = packStateStore.readPrevious()
        val base = checkNotNull(packStateStore.packDir("").parentFile) {
            "component=map-pack-state seam=reference-read class=parent-missing"
        }
        check((current != null || Files.notExists(File(base, "current.json").toPath(), NOFOLLOW_LINKS)) &&
            (previous != null || Files.notExists(File(base, "previous.json").toPath(), NOFOLLOW_LINKS))) {
            "component=map-pack-state seam=reference-read class=metadata-unreadable"
        }
        return current to previous
    }

    /** Store owns identity/link validation; no candidate is deleted during this scan. */
    private fun preflightStaleDirs(keepVersions: Set<String>): List<File> {
        val baseDir = checkNotNull(packStateStore.packDir("").parentFile) { EVICTION_TARGET_INVALID }
        if (Files.notExists(baseDir.toPath(), NOFOLLOW_LINKS)) return emptyList()
        return try {
            Files.newDirectoryStream(baseDir.toPath()).use { entries ->
                entries.mapNotNull { path ->
                    val file = path.toFile()
                    if (!file.name.startsWith("v") ||
                        (!Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))) null
                    else {
                        val version = file.name.drop(1)
                        val target = checkNotNull(packStateStore.installTarget(version)) { EVICTION_TARGET_INVALID }
                        target.takeUnless { version in keepVersions }
                    }
                }
            }
        } catch (_: IOException) {
            error(EVICTION_TREE_UNREADABLE)
        } catch (_: DirectoryIteratorException) {
            error(EVICTION_TREE_UNREADABLE)
        } catch (_: SecurityException) {
            error(EVICTION_TREE_UNREADABLE)
        }
    }

    private fun deleteStaleDirs(staleDirs: List<File>) {
        try {
            staleDirs.forEach { dir ->
                // No FOLLOW_LINKS: walk the validated tree's own entries,
                // unlike File.deleteRecursively(), which follows directory links.
                Files.walk(dir.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            }
        } catch (_: IOException) {
            error(EVICTION_DELETE_FAILED)
        } catch (_: UncheckedIOException) {
            error(EVICTION_DELETE_FAILED)
        } catch (_: SecurityException) {
            error(EVICTION_DELETE_FAILED)
        }
    }
}
