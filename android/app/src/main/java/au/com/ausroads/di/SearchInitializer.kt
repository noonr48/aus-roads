package au.com.ausroads.di

import android.content.Context
import android.util.Log
import au.com.ausroads.io.isExpectedSqliteDatabase
import au.com.ausroads.io.readCurrentInstalledPack
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.installDirectoryName
import au.com.ausroads.offline.search.FtsSearchRepository
import au.com.ausroads.offline.search.PreparedSearchDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val searchRepository: FtsSearchRepository,
) {
    /**
     * Open the canonical installed pack's index. A matching Adelaide index is
     * bundled as a guaranteed fresh-install fallback, so the visible search box
     * never starts life connected to an empty repository.
     */
    fun initialize(
        installedDescriptor: InstalledPack? = readCurrentInstalledPack(context.filesDir),
    ) {
        prepare(installedDescriptor).use(::activate)
    }

    /** Validate and open a candidate without changing the currently searchable generation. */
    fun prepare(
        installedDescriptor: InstalledPack? = readCurrentInstalledPack(context.filesDir),
    ): PreparedSearchDatabase {
        if (installedDescriptor != null) {
            val installed = checkNotNull(
                resolveInstalledSearchDatabase(context.filesDir, installedDescriptor),
            ) { "Installed map pack has no usable search component; repair is required" }
            return prepareDatabase(installed, "installed")
        }

        val bundled = ensureBundledSearchDatabase()
        return try {
            prepareDatabase(bundled, "bundled")
        } catch (firstError: android.database.sqlite.SQLiteException) {
            Log.w(TAG, "Bundled search cache was unusable; replacing it from the APK once")
            val repaired = ensureBundledSearchDatabase(force = true)
            runCatching { prepareDatabase(repaired, "repaired bundled") }
                .getOrElse { repairError ->
                    repairError.addSuppressed(firstError)
                    throw repairError
                }
        }
    }

    /** Non-throwing commit of a candidate that has already passed every I/O check. */
    fun activate(prepared: PreparedSearchDatabase) {
        searchRepository.activate(prepared)
    }

    private fun prepareDatabase(database: File, source: String): PreparedSearchDatabase {
        Log.i(
            TAG,
            "Preparing $source search DB (${database.length()} bytes)",
        )
        return searchRepository.prepare(database.absolutePath)
    }

    private fun ensureBundledSearchDatabase(force: Boolean = false): File {
        val directory = File(context.filesDir, BUNDLED_DIRECTORY).apply { mkdirs() }
        val output = File(directory, BUNDLED_FILENAME)
        val expectedHash = context.assets.open(BUNDLED_HASH_ASSET).bufferedReader().use { reader ->
            reader.readText().trim().substringBefore(' ').also { hash ->
                require(hash.matches(SHA256_PATTERN)) { "Invalid bundled search checksum" }
            }
        }
        if (!force && isExpectedSqliteDatabase(output, expectedHash)) return output

        val temporary = File(directory, "$BUNDLED_FILENAME.tmp")
        temporary.delete()
        context.assets.open(BUNDLED_ASSET).use { input ->
            temporary.outputStream().use(input::copyTo)
        }
        check(isExpectedSqliteDatabase(temporary, expectedHash)) {
            "Bundled search asset failed its SQLite/checksum contract"
        }
        if (!temporary.renameTo(output)) {
            temporary.copyTo(output, overwrite = true)
            temporary.delete()
        }
        return output
    }

    private companion object {
        private const val TAG = "SearchInitializer"
        private const val BUNDLED_ASSET = "maptile/adelaide-test-search-v1.db"
        private const val BUNDLED_HASH_ASSET = "$BUNDLED_ASSET.sha256"
        private const val BUNDLED_DIRECTORY = "bundled-map"
        private const val BUNDLED_FILENAME = "adelaide-test-search-v1.db"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * Resolve the same `current.json` generation used by the map. A stale flat or
 * lexicographically newer directory must never override an explicit current
 * pack (including rollback to an older version or a non-default search path).
 */
internal fun resolveInstalledSearchDatabase(filesDir: File): File? {
    val base = File(filesDir, "mappacks/au-sa")
    val current = File(base, "current.json")
    if (!current.isFile) return null
    val descriptor = readCurrentInstalledPack(filesDir) ?: return null
    return resolveInstalledSearchDatabase(filesDir, descriptor)
}

internal fun hasInstalledPackDescriptor(filesDir: File): Boolean =
    readCurrentInstalledPack(filesDir) != null

private fun resolveInstalledSearchDatabase(filesDir: File, descriptor: InstalledPack): File? {
    val base = File(filesDir, "mappacks/au-sa")
    val searchPath = descriptor.searchPath.orEmpty().ifBlank { "search.db" }
    return File(File(base, descriptor.installDirectoryName()), searchPath)
        .takeIf { it.isFile && it.length() > 0 }
}
