package au.com.ausroads.offline.download.eviction

import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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
    suspend fun onNewInstall(newPack: InstalledPack) = withContext(Dispatchers.IO) {
        // The persisted-state READ joins the shared suppression-protocol lock too:
        // a tombstone (deleteInstalled) completing between an out-of-lock read
        // and this section could resurrect a just-deleted version as
        // previous.json. Stale-dir deletion stays outside the critical section.
        var keepVersions: Set<String> = setOf(newPack.version)
        stateMutex.withLock {
            val current = packStateStore.readCurrent()
            if (current != null) {
                packStateStore.writePrevious(current)
                keepVersions = keepVersions + current.version
            }
            packStateStore.writeCurrent(newPack)
        }
        deleteStaleDirs(keepVersions = keepVersions)
    }

    /**
     * On app launch: validate current.json references an existing dir.
     * If not, revert to previous.json. Delete unreferenced v* dirs.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val current = packStateStore.readCurrent()
        val previous = packStateStore.readPrevious()

        val currentDir = current?.let { packStateStore.packDir(it.version) }
        val currentValid = currentDir != null && currentDir.exists() && currentDir.isDirectory

        if (!currentValid && current != null) {
            // Current is invalid — try reverting to previous
            val previousDir = previous?.let { packStateStore.packDir(it.version) }
            val previousValid = previousDir != null && previousDir.exists() && previousDir.isDirectory

            if (previousValid) {
                stateMutex.withLock {
                    packStateStore.writeCurrent(previous!!)
                    packStateStore.writePrevious(null)
                }
                deleteStaleDirs(keepVersions = setOf(previous.version))
            } else {
                // Both invalid — clear state (writes under the shared lock so a
                // concurrent delete/adopt cannot interleave into the swap).
                stateMutex.withLock {
                    packStateStore.writeCurrent(InstalledPack(
                        version = "",
                        regionCode = "",
                        installedAt = kotlinx.datetime.Clock.System.now(),
                        totalSizeBytes = 0,
                        tilesPath = "",
                        manifestSha256 = "",
                    ))
                    packStateStore.writePrevious(null)
                }
                deleteStaleDirs(keepVersions = emptySet())
            }
        } else {
            val keepVersions = mutableSetOf<String>()
            current?.let { keepVersions.add(it.version) }
            previous?.let { keepVersions.add(it.version) }
            deleteStaleDirs(keepVersions = keepVersions)
        }
    }

    private fun deleteStaleDirs(keepVersions: Set<String>) {
        val baseDir = File(packStateStore.packDir("").parentFile ?: return, "")
        if (!baseDir.exists()) return

        baseDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("v") && file.name !in keepVersions.map { "v$it" }) {
                file.deleteRecursively()
            }
        }
    }
}
