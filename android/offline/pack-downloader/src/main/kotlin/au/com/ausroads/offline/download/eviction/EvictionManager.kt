package au.com.ausroads.offline.download.eviction

import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class EvictionManager(private val packStateStore: PackStateStore) {

    /**
     * On new install: promote current → previous, write new current, delete stale dirs.
     */
    suspend fun onNewInstall(newPack: InstalledPack) = withContext(Dispatchers.IO) {
        val current = packStateStore.readCurrent()
        if (current != null) {
            packStateStore.writePrevious(current)
        }
        packStateStore.writeCurrent(newPack)
        deleteStaleDirs(keepVersions = setOf(newPack.version, current?.version).filterNotNull().toSet())
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
                packStateStore.writeCurrent(previous!!)
                packStateStore.writePrevious(null)
                deleteStaleDirs(keepVersions = setOf(previous.version))
            } else {
                // Both invalid — clear state
                packStateStore.writeCurrent(InstalledPack(
                    version = "",
                    regionCode = "",
                    installedAt = kotlinx.datetime.Clock.System.now(),
                    totalSizeBytes = 0,
                    tilesPath = "",
                    manifestSha256 = "",
                ))
                packStateStore.writePrevious(null)
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
