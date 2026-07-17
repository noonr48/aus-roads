package au.com.ausroads.offline.download.state

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption
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
        readJsonFile<InstalledPack>(File(baseDir, "current.json"))
            ?.takeIf(InstalledPack::hasSafeStorageIdentity)
    }

    suspend fun readPrevious(): InstalledPack? = withContext(Dispatchers.IO) {
        readJsonFile<InstalledPack>(File(baseDir, "previous.json"))
            ?.takeIf(InstalledPack::hasSafeStorageIdentity)
    }

    suspend fun writeCurrent(pack: InstalledPack) = withContext(Dispatchers.IO) {
        baseDir.mkdirs()
        writeJsonFile(File(baseDir, "current.json"), pack)
    }

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

    fun packDir(pack: InstalledPack): File = File(baseDir, pack.installDirectoryName())

    /** A WorkManager UUID makes re-downloads of the same version physically distinct. */
    fun generationDir(version: String, generationId: String): File {
        require(version.matches(SAFE_PATH_SEGMENT)) { "Unsafe pack version" }
        require(generationId.matches(SAFE_PATH_SEGMENT)) { "Unsafe pack generation" }
        return File(baseDir, "v$version-$generationId")
    }

    fun regionDir(): File = baseDir

    fun currentDescriptorExists(): Boolean = File(baseDir, "current.json").isFile

    fun partialDir(): File = File(baseDir, ".partial")

    /** Scratch directory verified before it replaces a live version directory. */
    fun stagingDir(version: String): File = File(baseDir, ".install-v$version")

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
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(json.encodeToString(value).toByteArray())
            output.fd.sync()
        }
        java.nio.file.Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private companion object {
        val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._-]+")
    }
}
