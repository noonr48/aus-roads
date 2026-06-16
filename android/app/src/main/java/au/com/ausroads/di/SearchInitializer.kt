package au.com.ausroads.di

import android.content.Context
import android.util.Log
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
     * Open the search database from the installed pack directory. Called on app
     * launch and again whenever a new pack is installed (see AusRoadsApp), so a
     * pack downloaded in-session is picked up without a full restart.
     */
    fun initialize() {
        // Try the flat installed path first.
        val flat = File(context.filesDir, "mappacks/au-sa/search.db")
        if (flat.exists()) {
            Log.i(TAG, "Opening search DB (flat): ${flat.absolutePath} (${flat.length()} bytes)")
            searchRepository.open(flat.absolutePath)
            return
        }

        // Fall back to the latest versioned pack directory (v<version>/search.db).
        val mappacksDir = File(context.filesDir, "mappacks/au-sa")
        val latestVersion = mappacksDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("v") }
            ?.maxByOrNull { it.name }
        val versioned = latestVersion?.let { File(it, "search.db") }
        if (versioned != null && versioned.exists()) {
            Log.i(
                TAG,
                "Opening search DB (versioned ${latestVersion.name}): " +
                    "${versioned.absolutePath} (${versioned.length()} bytes)",
            )
            searchRepository.open(versioned.absolutePath)
            return
        }

        // No search DB available — search will return empty until a pack is installed.
        Log.w(
            TAG,
            "No search.db found under ${mappacksDir.absolutePath} " +
                "(dirs: ${mappacksDir.listFiles()?.joinToString { it.name } ?: "<none>"}); " +
                "search returns empty until a pack with a search index is installed",
        )
    }

    private companion object {
        private const val TAG = "SearchInitializer"
    }
}
