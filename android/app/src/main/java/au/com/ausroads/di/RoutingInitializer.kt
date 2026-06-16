package au.com.ausroads.di

import android.content.Context
import android.util.Log
import au.com.ausroads.routing.engine.valhalla.ValhallaRoutingEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initializes the Valhalla routing engine from installed routing tiles, if
 * present. Mirrors [SearchInitializer]: directions stay gracefully unavailable
 * (RouteViewModel surfaces a "routing not available" message) until a pack with
 * routing tiles is installed.
 *
 * Injects the concrete [ValhallaRoutingEngine] because `initialize(tarPath)` is
 * engine-specific. It is the same @Singleton instance RouteViewModel resolves
 * via the RoutingEngine interface, so initializing here makes routing ready
 * app-wide.
 */
@Singleton
class RoutingInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routingEngine: ValhallaRoutingEngine,
) {
    fun initialize() {
        val tar = resolveRoutingTar()
        if (tar == null) {
            Log.i(TAG, "No routing tiles installed; directions unavailable until a routing pack is installed")
            return
        }
        routingEngine.initialize(tar.absolutePath)
        Log.i(TAG, "Routing initialized from ${tar.absolutePath} (${tar.length()} bytes)")
    }

    private fun resolveRoutingTar(): File? {
        val base = File(context.filesDir, MAPPACK_DIR)
        val conventional = File(base, "routing/$TAR_NAME")
        if (conventional.isFile && conventional.length() > 0) return conventional

        // Fall back to the latest versioned pack dir's routing component.
        val latest = base.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("v") }
            ?.maxByOrNull { it.name }
            ?: return null
        val candidates = listOf(
            File(latest, "routing/$TAR_NAME"),
            File(latest, TAR_NAME),
        )
        candidates.firstOrNull { it.isFile && it.length() > 0 }?.let { return it }
        return File(latest, "routing").listFiles()
            ?.firstOrNull { it.isFile && it.extension == "tar" && it.length() > 0 }
    }

    private companion object {
        private const val TAG = "RoutingInitializer"
        private const val MAPPACK_DIR = "mappacks/au-sa"
        private const val TAR_NAME = "valhalla_tiles.tar"
    }
}
