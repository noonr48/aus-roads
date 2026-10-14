package au.com.ausroads.offline.download.state

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.datetime.Clock

class PackStateStore(
    private val context: Context,
    private val clock: Clock = Clock.System,
) {
    internal companion object {
        const val TARGET_INVALID = "pack_target_invalid"
        const val SCRATCH_INVALID = "pack_scratch_invalid"

        /** Complete, non-mutating preflight; missing trees are valid, unknown/link trees are not. */
        internal fun unlinkedTree(dir: File): List<Path>? = try {
            when {
                Files.notExists(dir.toPath(), NOFOLLOW_LINKS) -> emptyList()
                !Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS) -> null
                else -> Files.walk(dir.toPath()).use { paths ->
                    paths.iterator().asSequence().toList().takeIf { entries ->
                        entries.all { entry ->
                            val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                            // A hard alias is still a regular file: writing it would
                            // overwrite another owner's inode before any later refusal.
                            attributes.isDirectory || (attributes.isRegularFile &&
                                (Files.getAttribute(entry, "unix:nlink", NOFOLLOW_LINKS) as? Number)?.toLong() == 1L)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: UncheckedIOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: UnsupportedOperationException) {
            null // Unknown provider metadata cannot grant write/delete authority.
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val baseDir: File
        get() = File(context.filesDir, "mappacks/au-sa")

    suspend fun readCurrent(): InstalledPack? = withContext(Dispatchers.IO) {
        readInstalledPack(File(baseDir, "current.json"))
    }

    suspend fun readPrevious(): InstalledPack? = withContext(Dispatchers.IO) {
        readInstalledPack(File(baseDir, "previous.json"))
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

    // Empty is an existing base-dir locator, never an install/cleanup identity.
    fun packDir(version: String): File = if (version.isEmpty()) {
        requireNotNull(canonicalBaseDir()) { TARGET_INVALID }
        File(baseDir, "v")
    } else requireNotNull(installTarget(version)) { TARGET_INVALID }

    /** One identity rule for every caller; missing directories are valid before extraction. */
    internal fun installTarget(version: String): File? {
        if (version.isBlank() || version == "." || version == ".." ||
            version.any { it == '/' || it == '\\' || it.isISOControl() }) return null
        return containedDirectory("v$version")
    }

    /** The same trusted root/direct-child/tree identity precedes install and scratch IO. */
    private fun containedDirectory(name: String): File? {
        return try {
            val root = canonicalBaseDir() ?: return null
            val target = File(baseDir, name)
            if (target.canonicalFile != File(root, name) || unlinkedTree(target) == null) return null
            if (name == ".partial") {
                val zip = File(target, "pack.zip").toPath()
                if (!Files.notExists(zip, NOFOLLOW_LINKS) && !Files.isRegularFile(zip, NOFOLLOW_LINKS)) return null
            }
            target
        } catch (_: IOException) {
            null
        } catch (_: UncheckedIOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun canonicalBaseDir(): File? = try {
        // Trust Context.filesDir's canonical root (Android may alias it), not a
        // substituted mappacks/region directory underneath that root.
        val root = File(context.filesDir.canonicalFile, "mappacks/au-sa")
        val dir = baseDir
        if (dir.canonicalFile != root || listOf(dir.parentFile!!, dir).any { entry ->
            Files.isSymbolicLink(entry.toPath()) ||
                (!Files.notExists(entry.toPath(), NOFOLLOW_LINKS) && !Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS))
        }) null else root
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readInstalledPack(file: File): InstalledPack? {
        if (canonicalBaseDir() == null || Files.isSymbolicLink(file.toPath())) return null
        return readJsonFile<InstalledPack>(file)?.takeIf { installTarget(it.version) != null }
    }

    fun partialDir(): File = requireNotNull(containedDirectory(".partial")) { SCRATCH_INVALID }

    /** Only genuinely empty state is disposable; previous/unknown/resumable bytes are not. */
    suspend fun cleanupIfEmpty() = withContext(Dispatchers.IO) {
        if (canonicalBaseDir() == null) return@withContext
        val children = baseDir.listFiles() ?: return@withContext
        if (children.any { it.name != ".partial" }) return@withContext
        val scratch = containedDirectory(".partial") ?: return@withContext
        // delete() refuses a nonempty scratch/base and never traverses a link.
        if (scratch.exists() && (!scratch.isDirectory || !scratch.delete())) return@withContext
        baseDir.delete()
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
