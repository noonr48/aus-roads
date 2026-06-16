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
import org.json.JSONObject
import java.io.File

object MapPackAvailability {
    private const val BUNDLED_PACK_PATH = "maptile/adelaide-test-tiles.mbtiles"
    private const val BUNDLED_STYLE_PATH = "maptile/style.json"

    // Mirrors PackStateStore's layout in the pack-downloader module.
    private const val PACK_BASE_DIR = "mappacks/au-sa"
    private const val CURRENT_JSON = "current.json"
    private const val DEFAULT_TILES_PATH = "tiles.mbtiles"

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
     * pack is installed or its tiles file is missing. Reads `current.json`
     * directly (org.json, no extra deps) rather than depending on the
     * downloader's PackStateStore, which is Hilt-scoped.
     */
    fun installedPackMbtiles(context: Context): File? {
        val baseDir = File(context.filesDir, PACK_BASE_DIR)
        val currentFile = File(baseDir, CURRENT_JSON)
        if (!currentFile.exists()) return null
        val (version, tilesPath) = try {
            val obj = JSONObject(currentFile.readText())
            val v = obj.optString("version")
            val t = obj.optString("tilesPath", DEFAULT_TILES_PATH).ifBlank { DEFAULT_TILES_PATH }
            v to t
        } catch (_: Exception) {
            return null
        }
        if (version.isBlank()) return null
        val mbtiles = File(File(baseDir, "v$version"), tilesPath)
        return mbtiles.takeIf { it.exists() && it.length() > 0 }
    }

    /** True when either a downloaded pack or the bundled asset can render. */
    fun hasAnyPack(context: Context): Boolean =
        installedPackMbtiles(context) != null || hasBundledPack(context)

    /**
     * True when the map is rendering the bundled Adelaide demo tiles because no
     * full pack has been downloaded — i.e. coverage is limited to the Adelaide
     * area. Drives the "Demo map" coverage banner so the limited extent isn't a
     * silent surprise.
     */
    fun isUsingBundledFallback(context: Context): Boolean =
        installedPackMbtiles(context) == null && hasBundledPack(context)
}
