package au.com.ausroads.offline.search

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class FtsSearchRepository @Inject constructor() : SearchRepository {

    private var db: SQLiteDatabase? = null

    override fun open(dbPath: String) {
        db?.close()
        db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
    }

    override fun close() {
        db?.close()
        db = null
    }

    override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val database = db ?: return@withContext emptyList()
            if (query.isBlank()) return@withContext emptyList()

            val ftsQuery = toFtsMatchQuery(query)

            val sql = if (kind != null) {
                """
                SELECT s.name, s.kind, s.class, s.lat, s.lon
                FROM search_index f
                JOIN search_meta s ON f.rowid = s.id
                WHERE f.search_index MATCH ?
                  AND s.kind = ?
                ORDER BY rank
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT s.name, s.kind, s.class, s.lat, s.lon
                FROM search_index f
                JOIN search_meta s ON f.rowid = s.id
                WHERE f.search_index MATCH ?
                ORDER BY rank
                LIMIT ?
                """.trimIndent()
            }

            val cursor = if (kind != null) {
                database.rawQuery(sql, arrayOf(ftsQuery, kind, limit.toString()))
            } else {
                database.rawQuery(sql, arrayOf(ftsQuery, limit.toString()))
            }

            cursor.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            SearchResult(
                                name = c.getString(0),
                                kind = c.getString(1),
                                className = c.getString(2),
                                latitude = c.getDouble(3),
                                longitude = c.getDouble(4),
                            )
                        )
                    }
                }
            }
        }

    /**
     * Build a safe FTS5 MATCH expression from raw user input. Each whitespace
     * token is wrapped in a quoted string (embedded quotes doubled) with a
     * trailing `*` prefix match, so FTS5 operators (`"`, `(`, `*`, `:`, `^`,
     * `-`, AND/OR/NOT/NEAR) in the query are treated as literals and can never
     * produce a malformed MATCH that throws SQLiteException.
     */
    private fun toFtsMatchQuery(raw: String): String =
        raw.trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> "\"${token.replace("\"", "\"\"")}\"*" }

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
}
