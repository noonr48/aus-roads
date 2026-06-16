package au.com.ausroads.offline.search

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class FtsSearchRepository @Inject constructor() : SearchRepository {

    private var db: SQLiteDatabase? = null

    override fun open(dbPath: String) {
        db?.close()
        db = try {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).also { opened ->
                val rows = runCatching {
                    opened.rawQuery("SELECT count(*) FROM search_meta", null).use { c ->
                        if (c.moveToFirst()) c.getLong(0) else -1L
                    }
                }.getOrDefault(-1L)
                Log.i(TAG, "Opened search DB: $dbPath ($rows rows in search_meta)")
            }
        } catch (e: SQLiteException) {
            Log.e(TAG, "Failed to open search DB: $dbPath", e)
            null
        }
    }

    override fun close() {
        db?.close()
        db = null
    }

    override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val database = db ?: run {
                Log.w(TAG, "search skipped — no search DB open; returning empty")
                return@withContext emptyList()
            }
            val tokens = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@withContext emptyList()

            // Substring match per token against the plain search_meta table. The pack
            // also carries an FTS5 index, but Android's system SQLite does NOT reliably
            // ship the fts5 module (observed "no such module: fts5" on a GrapheneOS
            // Pixel), so a MATCH query throws. LIKE over ~128k rows is comfortably fast
            // for a debounced search box and works on every device; shorter names first.
            val likeClauses = tokens.joinToString(" AND ") { "name LIKE ? ESCAPE '\\'" }
            val kindClause = if (kind != null) " AND kind = ?" else ""
            val sql =
                """
                SELECT name, kind, class, lat, lon
                FROM search_meta
                WHERE $likeClauses$kindClause
                ORDER BY length(name), name
                LIMIT ?
                """.trimIndent()
            val args = buildList {
                tokens.forEach { add("%${escapeLike(it)}%") }
                if (kind != null) add(kind)
                add(limit.toString())
            }.toTypedArray()

            val results = try {
                database.rawQuery(sql, args).use { c -> c.toSearchResults() }
            } catch (e: SQLiteException) {
                Log.e(TAG, "search query failed", e)
                emptyList()
            }
            // Debug-only: contains the user's query text — stripped from release (proguard).
            Log.d(TAG, "search('$query') [${tokens.size} token(s)] -> ${results.size} results")
            results
        }

    /**
     * Escape LIKE wildcards in user input so `%`, `_` and `\` match literally
     * (paired with `ESCAPE '\'`) — e.g. a user typing `%` must not match everything.
     */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    override suspend fun nearest(
        latitude: Double,
        longitude: Double,
        kind: String?,
        maxDistanceDegrees: Double,
    ): SearchResult? =
        withContext(Dispatchers.IO) {
            val database = db ?: return@withContext null

            // Planar nearest-neighbour over the indexed feature metadata. A bbox
            // pre-filter keeps the scan cheap; squared planar distance is good
            // enough to pick the closest place label at SA latitudes. cos-scale
            // the longitude delta so distances are not skewed away from the equator.
            val cosLat = Math.cos(Math.toRadians(latitude))
            val sql = buildString {
                append(
                    """
                    SELECT name, kind, class, lat, lon,
                           ((lat - ?) * (lat - ?)) +
                           ((lon - ?) * (lon - ?) * ? * ?) AS dist2
                    FROM search_meta
                    WHERE lat IS NOT NULL AND lon IS NOT NULL
                      AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                    """.trimIndent(),
                )
                if (kind != null) append("\n  AND kind = ?")
                append("\nORDER BY dist2 ASC\nLIMIT 1")
            }
            val args = mutableListOf(
                latitude.toString(), latitude.toString(),
                longitude.toString(), longitude.toString(),
                cosLat.toString(), cosLat.toString(),
                (latitude - maxDistanceDegrees).toString(), (latitude + maxDistanceDegrees).toString(),
                (longitude - maxDistanceDegrees).toString(), (longitude + maxDistanceDegrees).toString(),
            )
            if (kind != null) args.add(kind)

            database.rawQuery(sql, args.toTypedArray()).use { c ->
                if (c.moveToNext()) {
                    SearchResult(
                        name = c.getString(0),
                        kind = c.getString(1),
                        className = c.getString(2),
                        latitude = c.getDouble(3),
                        longitude = c.getDouble(4),
                    )
                } else {
                    null
                }
            }
        }

    override suspend fun browseByCategory(
        category: PoiCategory,
        limit: Int,
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val database = db ?: return@withContext emptyList()
            if (category.classValues.isEmpty()) return@withContext emptyList()

            // Expand the category to one bind placeholder per OSM class value, so
            // the IN-list is fully parameterised (no string interpolation of data).
            val placeholders = category.classValues.joinToString(", ") { "?" }
            val sql =
                """
                SELECT name, kind, class, lat, lon
                FROM search_meta
                WHERE class IN ($placeholders)
                ORDER BY name
                LIMIT ?
                """.trimIndent()
            val args = category.classValues.toMutableList()
            args.add(limit.toString())

            database.rawQuery(sql, args.toTypedArray()).use { c ->
                c.toSearchResults()
            }
        }

    override suspend fun nearestByCategory(
        latitude: Double,
        longitude: Double,
        category: PoiCategory,
        limit: Int,
        maxDistanceDegrees: Double,
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val database = db ?: return@withContext emptyList()
            if (category.classValues.isEmpty()) return@withContext emptyList()

            // Same planar-nearest shape as nearest(): bbox pre-filter plus
            // cos-lat-scaled squared distance, additionally constrained to the
            // category's OSM class values. Returns up to `limit`, closest first.
            val cosLat = Math.cos(Math.toRadians(latitude))
            val placeholders = category.classValues.joinToString(", ") { "?" }
            val sql =
                """
                SELECT name, kind, class, lat, lon,
                       ((lat - ?) * (lat - ?)) +
                       ((lon - ?) * (lon - ?) * ? * ?) AS dist2
                FROM search_meta
                WHERE lat IS NOT NULL AND lon IS NOT NULL
                  AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                  AND class IN ($placeholders)
                ORDER BY dist2 ASC
                LIMIT ?
                """.trimIndent()
            val args = mutableListOf(
                latitude.toString(), latitude.toString(),
                longitude.toString(), longitude.toString(),
                cosLat.toString(), cosLat.toString(),
                (latitude - maxDistanceDegrees).toString(), (latitude + maxDistanceDegrees).toString(),
                (longitude - maxDistanceDegrees).toString(), (longitude + maxDistanceDegrees).toString(),
            )
            args.addAll(category.classValues)
            args.add(limit.toString())

            database.rawQuery(sql, args.toTypedArray()).use { c ->
                c.toSearchResults()
            }
        }

    override suspend fun maxspeedNear(
        latitude: Double,
        longitude: Double,
        maxDistanceDegrees: Double,
    ): Int? =
        withContext(Dispatchers.IO) {
            val database = db ?: return@withContext null

            // Planar nearest over the optional road_speed table. Old packs were
            // built before this table existed, so a missing-table SQLiteException
            // is expected and degrades to null rather than propagating.
            val cosLat = Math.cos(Math.toRadians(latitude))
            val sql =
                """
                SELECT maxspeed_kmh
                FROM road_speed
                WHERE lat IS NOT NULL AND lon IS NOT NULL
                  AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                ORDER BY ((lat - ?) * (lat - ?)) +
                         ((lon - ?) * (lon - ?) * ? * ?) ASC
                LIMIT 1
                """.trimIndent()
            val args = arrayOf(
                (latitude - maxDistanceDegrees).toString(), (latitude + maxDistanceDegrees).toString(),
                (longitude - maxDistanceDegrees).toString(), (longitude + maxDistanceDegrees).toString(),
                latitude.toString(), latitude.toString(),
                longitude.toString(), longitude.toString(),
                cosLat.toString(), cosLat.toString(),
            )

            try {
                database.rawQuery(sql, args).use { c ->
                    if (c.moveToNext() && !c.isNull(0)) c.getInt(0) else null
                }
            } catch (_: SQLiteException) {
                // road_speed table absent (pre-speed-layer pack): no data available.
                null
            }
        }

    /**
     * Drain a cursor positioned before its first row into [SearchResult]s,
     * reading the leading `name, kind, class, lat, lon` columns. Extra trailing
     * columns (e.g. a computed `dist2`) are ignored.
     */
    private fun android.database.Cursor.toSearchResults(): List<SearchResult> =
        buildList {
            while (moveToNext()) {
                add(
                    SearchResult(
                        name = getString(0),
                        kind = getString(1),
                        className = getString(2),
                        latitude = getDouble(3),
                        longitude = getDouble(4),
                    )
                )
            }
        }

    private companion object {
        private const val TAG = "FtsSearchRepository"
    }
}
