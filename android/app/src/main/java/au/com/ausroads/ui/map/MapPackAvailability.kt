/*
 * MapPackAvailability — reports which map pack the MapScreen can render.
 *
 * Two sources, in priority order:
 *   1. A per-region pack installed in filesDir by the in-app downloader
 *      (filesDir/mappacks/au-sa/current.json + v<version>/<tilesPath>).
 *   2. The bundled Adelaide test pack checked into
 *      app/src/main/assets/maptile/, so debug and side-loaded builds always
 *      have something to render.
 */
package au.com.ausroads.ui.map

import android.content.Context
import au.com.ausroads.io.hasSqliteHeader
import au.com.ausroads.io.readCurrentInstalledPack
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.installDirectoryName
import java.io.File

object MapPackAvailability {
    private const val BUNDLED_PACK_PATH = "maptile/adelaide-test-tiles.mbtiles"
    private const val BUNDLED_STYLE_PATH = "maptile/style.json"

    // Mirrors PackStateStore's layout in the pack-downloader module.
    private const val PACK_BASE_DIR = "mappacks/au-sa"

    fun hasBundledPack(context: Context): Boolean =
        try {
            context.assets.open(BUNDLED_PACK_PATH).use { true }
        } catch (_: Exception) {
            false
        }

    fun hasBundledStyle(context: Context): Boolean =
        try {
            context.assets.open(BUNDLED_STYLE_PATH).use { true }
        } catch (_: Exception) {
            false
        }

    /**
     * The MBTiles file of the pack installed by the downloader, or null when no
     * pack is installed or its tiles file is missing. Reads the downloader's
     * canonical `current.json` schema through the shared pack-state decoder.
     */
    fun installedPackMbtiles(context: Context): File? {
        val descriptor = installedDescriptor(context) ?: return null
        return installedPackMbtiles(context, descriptor)
    }

    /** Resolve an explicitly activated descriptor instead of re-reading current.json. */
    fun installedPackMbtiles(context: Context, descriptor: InstalledPack): File? {
        val baseDir = File(context.filesDir, PACK_BASE_DIR)
        val mbtiles = File(File(baseDir, descriptor.installDirectoryName()), descriptor.tilesPath)
        return mbtiles.takeIf(::hasSqliteHeader)
    }

    /** Current disk descriptor, used only before the runtime coordinator settles. */
    fun installedPack(context: Context): InstalledPack? = installedDescriptor(context)

    /** Stable key matching MapPackManager's InstalledPack identity on first composition. */
    fun installedPackIdentity(context: Context): String? = installedDescriptor(context)?.let {
        "${it.version}:${it.installedAt}:${it.installDirectoryName()}"
    }

    /** True when either a downloaded pack or the bundled asset can render. */
    fun hasAnyPack(context: Context): Boolean = if (hasInstalledPackDescriptor(context)) {
        installedPackMbtiles(context) != null
    } else {
        hasBundledPack(context)
    }

    /**
     * True when the map is rendering the bundled Adelaide demo tiles because no
     * full pack has been downloaded — i.e. coverage is limited to the Adelaide
     * area. Drives the "Demo map" coverage banner so the limited extent isn't a
     * silent surprise.
     */
    fun isUsingBundledFallback(context: Context): Boolean =
        !hasInstalledPackDescriptor(context) && hasBundledPack(context)

    fun hasInstalledPackDescriptor(context: Context): Boolean =
        installedDescriptor(context) != null

    private fun installedDescriptor(context: Context): InstalledPack? =
        readCurrentInstalledPack(context.filesDir)
}
