package au.com.ausroads.offline.download.eviction

import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.offline.download.state.installDirectoryName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class EvictionManager(private val packStateStore: PackStateStore) {
    private val mutationMutex = Mutex()

    /**
     * Atomically promote verified bytes, then promote current → previous, write
     * new current, and delete stale dirs. The optional [promoteVerifiedDirectory]
     * callback must return a user-facing error without mutating pack state when
     * filesystem promotion fails.
     */
    suspend fun onNewInstall(
        newPack: InstalledPack,
        promoteVerifiedDirectory: () -> String? = { null },
    ): String? = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            promoteVerifiedDirectory()?.let { return@withLock it }
            val current = packStateStore.readCurrent()
            if (current != null) {
                packStateStore.writePrevious(current)
            }
            packStateStore.writeCurrent(newPack)
            deleteStaleDirs(keepPacks = listOfNotNull(newPack, current))
            null
        }
    }

    /**
     * Roll disk state back when native/search preparation rejects the just-installed
     * current generation. The identity check avoids undoing a newer install that
     * arrived while the failed candidate was being prepared.
     */
    suspend fun rollbackFailedInstall(failedPack: InstalledPack): InstalledPack? =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val current = packStateStore.readCurrent()
                if (current != failedPack) return@withLock current

                val previous = packStateStore.readPrevious()
                val restored = previous?.takeIf(::hasUsableDirectory)
                if (restored != null) {
                    packStateStore.writeCurrent(restored)
                } else {
                    packStateStore.clearCurrent()
                }
                packStateStore.writePrevious(null)
                deleteStaleDirs(keepPacks = listOfNotNull(restored))
                restored
            }
        }

    /**
     * On app launch: validate current.json references an existing dir.
     * If not, revert to previous.json. Delete unreferenced v* dirs.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val current = packStateStore.readCurrent()
            val previous = packStateStore.readPrevious()

            val currentValid = current?.let(::hasUsableDirectory) == true
            val malformedCurrent = current == null && packStateStore.currentDescriptorExists()

            if (!currentValid && (current != null || malformedCurrent || previous != null)) {
                // Current is invalid — try reverting to previous
                val previousValid = previous?.let(::hasUsableDirectory) == true

                if (previousValid) {
                    packStateStore.writeCurrent(previous!!)
                    packStateStore.writePrevious(null)
                    deleteStaleDirs(keepPacks = listOf(previous))
                } else {
                    // Both invalid — clear state
                    packStateStore.clearCurrent()
                    packStateStore.writePrevious(null)
                    deleteStaleDirs(keepPacks = emptyList())
                }
            } else {
                deleteStaleDirs(keepPacks = listOfNotNull(current, previous))
            }
        }
    }

    private fun hasUsableDirectory(pack: InstalledPack): Boolean {
        val directory = packStateStore.packDir(pack)
        val tiles = File(directory, pack.tilesPath)
        return directory.isDirectory && tiles.isFile && tiles.length() > 0L
    }

    private fun deleteStaleDirs(keepPacks: Collection<InstalledPack>) {
        val keepDirectoryNames = keepPacks.mapTo(mutableSetOf()) { it.installDirectoryName() }
        val baseDir = packStateStore.regionDir()
        if (!baseDir.exists()) return

        baseDir.listFiles()?.forEach { file ->
            if (file.isDirectory && file.name.startsWith("v") && file.name !in keepDirectoryNames) {
                file.deleteRecursively()
            }
        }
    }
}
