package au.com.ausroads.ui.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import au.com.ausroads.offline.download.state.InstalledPack
import java.io.File
import org.json.JSONObject

internal data class PreparedMapStyle(val json: String, val tileFile: File, val usingBundled: Boolean)

/** File/hash/SQLite/style reads belong to the IO phase, never getMapAsync/main. */
internal class MapAssetPreparer(private val context: Context) {
    private companion object { const val MAP_STYLE_VERSION = 8 }
    fun prepare(selectedPack: InstalledPack? = null): PreparedMapStyle {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "component=map seam=asset-io-thread class=contract-drift"
        }
        val installed = selectedPack?.let { pack ->
            val adopted = requireNotNull(MapPackAvailability.installedPackMbtiles(context)) {
                "component=map seam=pack-adoption class=not-joined"
            }
            // Installer paths are relative to the version directory, not filesDir.
            val requested = File(pack.tilesPath)
            val expected = if (requested.isAbsolute) requested else {
                File(context.filesDir, "mappacks/au-sa/v${pack.version}/${pack.tilesPath}")
            }
            check(expected.canonicalFile == adopted.canonicalFile) {
                "component=map seam=pack-adoption class=contract-drift"
            }
            adopted
        }
        val tileFile = installed ?: ValidatedMapAssetCache.ensure(
            File(context.filesDir, "adelaide-test-tiles.mbtiles"),
            { context.assets.open("maptile/adelaide-test-tiles.mbtiles") },
            ::validateTiles,
        )
        if (installed != null) validateTiles(installed)
        val json = context.assets.open("maptile/style.json").bufferedReader().use { it.readText() }
        val parsed = JSONObject(json)
        check(parsed.getInt("version") == MAP_STYLE_VERSION && parsed.getJSONObject("sources").length() > 0) {
            "component=map seam=style-source class=contract-drift"
        }
        val sourceUri = "asset://maptile/adelaide-test-tiles.mbtiles"
        check(json.contains(sourceUri)) { "component=map seam=style-source class=contract-drift" }
        return PreparedMapStyle(
            json.replace(sourceUri, "mbtiles://${tileFile.absolutePath}"),
            tileFile,
            installed == null,
        )
    }

    private fun validateTiles(file: File) {
        check(file.isFile && file.length() > 0) { "component=map seam=tiles-shape class=contract-drift" }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use {
                check(it.moveToFirst() && it.getString(0) == "ok") {
                    "component=map seam=tiles-integrity class=contract-drift"
                }
            }
            db.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use {
                check(it.moveToFirst() && !it.isNull(0) && it.getBlob(0).isNotEmpty()) {
                    "component=map seam=tiles-content class=contract-drift"
                }
            }
            db.rawQuery("SELECT name,value FROM metadata LIMIT 1", null).use {
                check(it.moveToFirst()) { "component=map seam=tiles-metadata class=contract-drift" }
            }
        }
    }
}
