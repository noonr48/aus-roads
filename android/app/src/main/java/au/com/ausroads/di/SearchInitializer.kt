package au.com.ausroads.di

import android.content.Context
import au.com.ausroads.offline.search.SearchRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val searchRepository: SearchRepository,
) {
    /**
     * Open the search database from the installed pack directory.
     * Called on app launch after PackStateStore reconciliation.
     * Falls back to bundled asset if no pack is installed.
     */
    fun initialize() {
        // Try installed pack directory first
        val packSearchDb = File(context.filesDir, "mappacks/au-sa/search.db")
        if (packSearchDb.exists()) {
            searchRepository.open(packSearchDb.absolutePath)
            return
        }

        // Try the latest versioned directory
        val mappacksDir = File(context.filesDir, "mappacks/au-sa")
        val latestVersion = mappacksDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("v") }
            ?.maxByOrNull { it.name }
        val versionedSearchDb = latestVersion?.let { File(it, "search.db") }
        if (versionedSearchDb != null && versionedSearchDb.exists()) {
            searchRepository.open(versionedSearchDb.absolutePath)
            return
        }

        // No search DB available — search will return empty results
    }
}
