package au.com.ausroads.offline.download.state

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.datetime.Clock

class PackStateStore(
    private val context: Context,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseDir: File
        get() = File(context.filesDir, "mappacks/au-sa")

    suspend fun readCurrent(): InstalledPack? = withContext(Dispatchers.IO) {
        readJsonFile(File(baseDir, "current.json"))
    }

    suspend fun readPrevious(): InstalledPack? = withContext(Dispatchers.IO) {
        readJsonFile(File(baseDir, "previous.json"))
    }

    suspend fun writeCurrent(pack: InstalledPack) = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        writeJsonFile(File(baseDir, "current.json"), pack)
    }

    /** Deletes the current-pack state file so an uninstalled pack cannot resurrect on restart. */
    suspend fun clearCurrent() = withContext(Dispatchers.IO) {
        File(baseDir, "current.json").delete()
    }

    suspend fun writePrevious(pack: InstalledPack?) = withContext(Dispatchers.IO) {
        val file = File(baseDir, "previous.json")
        if (pack == null) {
            file.delete()
        } else {
            writeJsonFile(file, pack)
        }
    }

    suspend fun readManifestCache(): ManifestCacheEntry? = withContext(Dispatchers.IO) {
        readJsonFile(File(baseDir, "manifest-cache.json"))
    }

    suspend fun writeManifestCache(entry: ManifestCacheEntry) = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        writeJsonFile(File(baseDir, "manifest-cache.json"), entry)
    }

    fun packDir(version: String): File = File(baseDir, "v$version")

    fun partialDir(): File = File(baseDir, ".partial")

    /**
     * Removes the region base dir (mappacks/au-sa) when it holds no installed pack:
     * no current.json and no v* install directories. Called from the download
     * worker's failure paths so an aborted first download doesn't leave a confusing
     * empty dir behind. A leftover .partial scratch dir is also cleared first so it
     * doesn't keep the base dir alive.
     */
    suspend fun cleanupIfEmpty() = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext
        if (File(baseDir, "current.json").exists()) return@withContext
        val hasInstalledVersion = baseDir.listFiles()
            ?.any { it.isDirectory && it.name.startsWith("v") } == true
        if (hasInstalledVersion) return@withContext
        // Clear scratch state, then drop the base dir if nothing else remains.
        partialDir().deleteRecursively()
        baseDir.deleteRecursively()
    }

    private inline fun <reified T> readJsonFile(file: File): T? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<T>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <reified T> writeJsonFile(file: File, value: T) {
        // Atomic replace: write a UNIQUE temp sibling then rename onto the target,
        // so an interrupted/crashed write leaves the PREVIOUS valid JSON intact
        // (rename overwrites atomically within one directory). The suffix is
        // unique per call: a crashed writer's leftover "<name>.tmp.<nanotime>"
        // must never collide with — or seal — a later writer's swap slot (a
        // fixed-name sibling previously let one poisoned temp break every future
        // write of that file).
        val tempFile = File(file.parentFile, file.name + ".tmp." + System.nanoTime())
        try {
            file.parentFile?.mkdirs()
            tempFile.writeText(json.encodeToString(value))
            check(tempFile.renameTo(file)) { "Failed to atomically replace $file" }
        } finally {
            // No scratch state survives any failure path (success = already moved);
            // only THIS writer's own temp is ever removed.
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
