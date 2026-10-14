package au.com.ausroads.offline.search

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Singleton
class FtsSearchRepository @Inject constructor() : SearchRepository, PlaceDiscoveryRepository {

    private val databaseLock = Any()
    private var db: SQLiteDatabase? = null
    private val _availability = MutableStateFlow(DiscoveryAvailability.LOADING)
    override val availability = _availability.asStateFlow()
    private val _generation = MutableStateFlow(0L)
    override val generation = _generation.asStateFlow()

    @Suppress(
        "TooGenericExceptionCaught"
    ) // invalid/corrupt index must publish FAILED without crashing boot
    override fun open(dbPath: String): Unit =
        synchronized(databaseLock) {
            _availability.value = DiscoveryAvailability.LOADING
            db?.close()
            db = null
            var opened: SQLiteDatabase? = null
            try {
                opened = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
                opened.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "ok") {
                        "Place catalog integrity failed"
                    }
                }
                validateCatalogRows(opened)
                db = opened
                _availability.value = DiscoveryAvailability.READY
            } catch (_: Exception) {
                opened?.close()
                _availability.value = DiscoveryAvailability.FAILED
                // Fixed metadata only: no query, coordinate, place name or private path.
                Log.e(TAG, "component=places seam=index-open class=contract-drift")
            } finally {
                _generation.value += 1L
            }
        }

    /** Validate stored values, not SQLite's coercions, including rows beyond the first cursor window. */
    private fun validateCatalogRows(database: SQLiteDatabase) {
        database.rawQuery("SELECT name, kind, class, lat, lon FROM search_meta", null).use { cursor ->
            check(cursor.moveToFirst()) { "Place catalog has no rows" }
            do {
                check(cursor.getType(0) == Cursor.FIELD_TYPE_STRING && cursor.getString(0).isNotBlank())
                check(cursor.getType(1) == Cursor.FIELD_TYPE_STRING)
                val kind = cursor.getString(1)
                check(kind in CATALOG_KINDS)
                check(cursor.isNull(2) || cursor.getType(2) == Cursor.FIELD_TYPE_STRING)
                val className = if (cursor.isNull(2)) null else cursor.getString(2)
                check(validCatalogClass(kind, className))
                check(validCoordinate(cursor, 3, LATITUDE_LIMIT))
                check(validCoordinate(cursor, 4, LONGITUDE_LIMIT))
            } while (cursor.moveToNext())
        }
    }

    private fun validCoordinate(cursor: Cursor, column: Int, limit: Double): Boolean {
        val type = cursor.getType(column)
        if (type != Cursor.FIELD_TYPE_INTEGER && type != Cursor.FIELD_TYPE_FLOAT) return false
        val value = cursor.getDouble(column)
        return value.isFinite() && value in -limit..limit
    }

    private fun validCatalogClass(kind: String, value: String?): Boolean {
        if (value == null) return true // legacy schema explicitly permits an unknown class
        if (value.isBlank()) return false
        return when (kind) {
            "poi" -> {
                val key = value.substringBefore('=', "")
                val tag = value.substringAfter('=', "")
                // Free-form OSM values include spaces/case/semicolon lists. Do not normalize identity.
                (key in POI_CLASS_KEYS && tag.isNotBlank()) ||
                    (key == "place" && tag in PLACE_CLASSES)
            }
            "park" -> value == "park" || value == "leisure=park"
            "water" -> value == "water" || value == "natural=water"
            "suburb" -> value.removePrefix("place=") in PLACE_CLASSES
            "road" -> '=' !in value // bare, open-ended OSM highway class, not a POI tag
            else -> false
        }
    }

    override fun close(): Unit =
        synchronized(databaseLock) {
            db?.close()
            db = null
            _availability.value = DiscoveryAvailability.UNAVAILABLE
            _generation.value += 1L
        }

    override suspend fun discover(request: DiscoveryRequest): List<SearchResult> =
        withContext(Dispatchers.IO) {
            synchronized(databaseLock) {
                val database = db ?: throw DiscoveryUnavailableException()
                val terms = DiscoveryTerms.parse(request.query)
                val categories =
                    when {
                        request.category != null ->
                            setOf(request.category)
                                .intersect(terms.categories.ifEmpty { setOf(request.category) })
                        terms.categories.isNotEmpty() -> terms.categories
                        else -> ExploreCategory.entries.toSet()
                    }
                categories
                    .flatMap { category ->
                        val query = DiscoverySql.build(request, category)
                        database.rawQuery(query.sql, query.args).use { it.toSearchResults() }
                    }
                    .distinctBy { it.placeKey() }
            }
        }

    override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> =
        withContext(Dispatchers.IO) {
            synchronized(databaseLock) {
                val database = db ?: throw DiscoveryUnavailableException()
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
                """
                        .trimIndent()
                val args = buildList {
                    tokens.forEach { add("%${escapeLike(it)}%") }
                    if (kind != null) add(kind)
                    add(limit.toString())
                }
                    .toTypedArray()

                val results =
                    try {
                        database.rawQuery(sql, args).use { c -> c.toSearchResults() }
                    } catch (e: SQLiteException) {
                        Log.e(TAG, "component=places seam=keyword-query class=contract-drift")
                        throw e
                    }
                results
            }
        }

    /**
     * Escape LIKE wildcards in user input so `%`, `_` and `\` match literally (paired with `ESCAPE
     * '\'`) — e.g. a user typing `%` must not match everything.
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
            synchronized(databaseLock) {
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
                        """
                            .trimIndent()
                    )
                    if (kind != null) append("\n  AND kind = ?")
                    append("\nORDER BY dist2 ASC\nLIMIT 1")
                }
                val args =
                    mutableListOf(
                        latitude.toString(),
                        latitude.toString(),
                        longitude.toString(),
                        longitude.toString(),
                        cosLat.toString(),
                        cosLat.toString(),
                        (latitude - maxDistanceDegrees).toString(),
                        (latitude + maxDistanceDegrees).toString(),
                        (longitude - maxDistanceDegrees).toString(),
                        (longitude + maxDistanceDegrees).toString(),
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
        }

    override suspend fun browseByCategory(
        category: PoiCategory,
        limit: Int,
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            synchronized(databaseLock) {
                val database = db ?: throw DiscoveryUnavailableException()
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
                """
                        .trimIndent()
                val args = category.classValues.toMutableList()
                args.add(limit.toString())

                database.rawQuery(sql, args.toTypedArray()).use { c ->
                    c.toSearchResults()
                }
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
            synchronized(databaseLock) {
                val database = db ?: throw DiscoveryUnavailableException()
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
                """
                        .trimIndent()
                val args =
                    mutableListOf(
                        latitude.toString(),
                        latitude.toString(),
                        longitude.toString(),
                        longitude.toString(),
                        cosLat.toString(),
                        cosLat.toString(),
                        (latitude - maxDistanceDegrees).toString(),
                        (latitude + maxDistanceDegrees).toString(),
                        (longitude - maxDistanceDegrees).toString(),
                        (longitude + maxDistanceDegrees).toString(),
                    )
                args.addAll(category.classValues)
                args.add(limit.toString())

                database.rawQuery(sql, args.toTypedArray()).use { c ->
                    c.toSearchResults()
                }
            }
        }

    override suspend fun maxspeedNear(
        latitude: Double,
        longitude: Double,
        maxDistanceDegrees: Double,
    ): Int? =
        withContext(Dispatchers.IO) {
            synchronized(databaseLock) {
                val database = db ?: return@withContext null

                // Old packs may omit this capability. A present but malformed table
                // is not absence: propagate it to the driver's fixed query witness.
                val hasSpeed = database.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                    arrayOf("road_speed"),
                ).use { it.moveToFirst() }
                if (!hasSpeed) return@withContext null
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
                    """
                        .trimIndent()
                val args =
                    arrayOf(
                        (latitude - maxDistanceDegrees).toString(),
                        (latitude + maxDistanceDegrees).toString(),
                        (longitude - maxDistanceDegrees).toString(),
                        (longitude + maxDistanceDegrees).toString(),
                        latitude.toString(),
                        latitude.toString(),
                        longitude.toString(),
                        longitude.toString(),
                        cosLat.toString(),
                        cosLat.toString(),
                    )

                database.rawQuery(sql, args).use { c ->
                    if (c.moveToNext() && !c.isNull(0)) c.getInt(0) else null
                }
            }
        }

    override suspend fun camerasNear(
        latitude: Double,
        longitude: Double,
        maxDistanceDegrees: Double,
        limit: Int,
    ): List<SpeedCameraPoint> =
        withContext(Dispatchers.IO) {
            synchronized(databaseLock) {
                val database = db ?: return@withContext emptyList()

                // Absence is a supported old-pack capability. A present but broken table
                // is a genuine query failure and must reach the caller's persistent witness.
                val hasCameras =
                    database
                        .rawQuery(
                            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                            arrayOf("road_cameras"),
                        )
                        .use { it.moveToFirst() }
                if (!hasCameras) return@withContext emptyList()
                val cosLat = Math.cos(Math.toRadians(latitude))
                val sql =
                    """
                    SELECT lat, lon, maxspeed_kmh, way_name
                    FROM road_cameras
                    WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
                    ORDER BY ((lat - ?) * (lat - ?)) +
                             ((lon - ?) * (lon - ?) * ? * ?) ASC
                    LIMIT ?
                    """
                        .trimIndent()
                val args =
                    arrayOf(
                        (latitude - maxDistanceDegrees).toString(),
                        (latitude + maxDistanceDegrees).toString(),
                        (longitude - maxDistanceDegrees).toString(),
                        (longitude + maxDistanceDegrees).toString(),
                        latitude.toString(),
                        latitude.toString(),
                        longitude.toString(),
                        longitude.toString(),
                        cosLat.toString(),
                        cosLat.toString(),
                        limit.toString(),
                    )

                database.rawQuery(sql, args).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            if (c.isNull(0) || c.isNull(1)) continue
                            add(
                                SpeedCameraPoint(
                                    latitude = c.getDouble(0),
                                    longitude = c.getDouble(1),
                                    maxspeedKmh = if (c.isNull(2)) null else c.getInt(2),
                                    wayName = if (c.isNull(3)) null else c.getString(3),
                                )
                            )
                        }
                    }
                }
            }
        }

    /**
     * Drain a cursor positioned before its first row into [SearchResult]s, reading the leading
     * `name, kind, class, lat, lon` columns. Extra trailing columns (e.g. a computed `dist2`) are
     * ignored.
     */
    private fun android.database.Cursor.toSearchResults(): List<SearchResult> = buildList {
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
        private const val LATITUDE_LIMIT = 90.0
        private const val LONGITUDE_LIMIT = 180.0
        private val CATALOG_KINDS = setOf("poi", "park", "road", "water", "suburb")
        private val POI_CLASS_KEYS = setOf("amenity", "tourism", "shop", "leisure")
        private val PLACE_CLASSES =
            setOf("suburb", "city", "town", "village", "locality", "neighbourhood", "hamlet", "quarter")
    }
}
