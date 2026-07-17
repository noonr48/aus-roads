package au.com.ausroads.di

import android.content.Context
import android.util.Log
import au.com.ausroads.io.readCurrentInstalledPack
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.installDirectoryName
import au.com.ausroads.routing.engine.valhalla.PreparedRoutingEngine
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
    fun initialize(
        installedDescriptor: InstalledPack? = readCurrentInstalledPack(context.filesDir),
    ) {
        prepare(installedDescriptor).use(::activate)
    }

    /** Construct a candidate without clearing the currently usable routing generation. */
    fun prepare(
        installedDescriptor: InstalledPack? = readCurrentInstalledPack(context.filesDir),
    ): PreparedRoutingEngine {
        val routingPath = installedDescriptor?.routingPath?.takeIf(String::isNotBlank)
        if (routingPath == null) {
            Log.i(TAG, "Prepared generation has no routing component")
            return routingEngine.prepare(null)
        }
        val tar = checkNotNull(resolveInstalledRoutingTar(context.filesDir, installedDescriptor)) {
            "Installed map pack declares missing routing tiles: $routingPath"
        }
        Log.i(TAG, "Preparing routing from ${tar.absolutePath} (${tar.length()} bytes)")
        return routingEngine.prepare(tar.absolutePath)
    }

    /** Non-throwing commit of a candidate that has already completed native construction. */
    fun activate(prepared: PreparedRoutingEngine) {
        routingEngine.activate(prepared)
    }

    private companion object {
        private const val TAG = "RoutingInitializer"
    }
}

/** Select routing from the same current.json generation as map and search. */
internal fun resolveInstalledRoutingTar(filesDir: File): File? {
    val base = File(filesDir, MAPPACK_DIR)
    val current = File(base, CURRENT_JSON)
    if (!current.isFile) return null
    val descriptor = readCurrentInstalledPack(filesDir) ?: return null
    return resolveInstalledRoutingTar(filesDir, descriptor)
}

private fun resolveInstalledRoutingTar(filesDir: File, descriptor: InstalledPack): File? {
    val base = File(filesDir, MAPPACK_DIR)
    val routingPath = descriptor.routingPath?.takeIf(String::isNotBlank) ?: return null
    return File(File(base, descriptor.installDirectoryName()), routingPath)
        .takeIf { it.isFile && it.length() > 0 }
}

private const val MAPPACK_DIR = "mappacks/au-sa"
private const val CURRENT_JSON = "current.json"
