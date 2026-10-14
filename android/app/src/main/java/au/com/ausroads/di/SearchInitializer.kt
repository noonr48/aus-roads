package au.com.ausroads.di

import android.content.Context
import android.util.AtomicFile
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.PlaceDiscoveryRepository
import au.com.ausroads.offline.search.SearchRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Fresh installs have a validated offline catalog; only adopted pack metadata can supersede it. */
@Singleton
class SearchInitializer
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val searchRepository: SearchRepository,
    private val discoveryRepository: PlaceDiscoveryRepository,
    private val health: PlacesHealth,
) {
    private val _usingBundled = MutableStateFlow(true)
    val usingBundled = _usingBundled.asStateFlow()

    @Synchronized
    @Suppress(
        "TooGenericExceptionCaught"
    ) // boot remains usable with an explicit unavailable catalog
    fun initialize() {
        try {
            val adopted = InstalledPackPaths.component(context, "searchPath", "search.db")
            if (adopted != null) {
                searchRepository.open(adopted.absolutePath)
                if (discoveryRepository.availability.value == DiscoveryAvailability.READY) {
                    _usingBundled.value = false
                    health.success(PlacesSeam.CATALOG_OPEN)
                    return
                }
                health.failure(PlacesSeam.CATALOG_OPEN)
            } else if (File(context.filesDir, "mappacks/au-sa/current.json").isFile) {
                // Rejected/missing adopted component: counted fallback, never scan orphan versions.
                health.failure(PlacesSeam.CATALOG_OPEN)
            }
            _usingBundled.value = true
            val bundled = ensureBundledCatalog()
            searchRepository.open(bundled.absolutePath)
            if (discoveryRepository.availability.value == DiscoveryAvailability.READY) {
                health.success(PlacesSeam.CATALOG_BOOTSTRAP)
            } else {
                health.failure(PlacesSeam.CATALOG_OPEN)
            }
        } catch (_: Exception) {
            searchRepository.close()
            health.failure(PlacesSeam.CATALOG_BOOTSTRAP)
        }
    }

    private fun ensureBundledCatalog(): File {
        val metadata =
            context.assets.open("catalog/sa-search.json").bufferedReader().use {
                JSONObject(it.readText())
            }
        check(metadata.getInt("schema") == 1)
        val expected = metadata.getString("sha256")
        check(Regex("[a-f0-9]{64}").matches(expected))
        val expectedBytes = metadata.getLong("bytes")
        val destination =
            File(context.noBackupFilesDir, "catalog-sa-${expected.take(CACHE_SUFFIX_LENGTH)}.db")
        if (
            destination.isFile &&
                destination.length() == expectedBytes &&
                sha256(destination) == expected
        ) {
            return destination
        }
        val atomic = AtomicFile(destination)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            context.assets.open("catalog/sa-search.db").use { it.copyTo(output) }
            // finishWrite fsyncs then atomically adopts the derived public-data cache.
            atomic.finishWrite(output)
            output = null
            check(destination.length() == expectedBytes && sha256(destination) == expected)
        } finally {
            if (output != null) atomic.failWrite(output)
        }
        return destination
    }

    private companion object {
        const val CACHE_SUFFIX_LENGTH = 16
        const val BYTE_MASK = 0xff
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(java.util.Locale.ROOT, it.toInt() and BYTE_MASK)
        }
    }
}
