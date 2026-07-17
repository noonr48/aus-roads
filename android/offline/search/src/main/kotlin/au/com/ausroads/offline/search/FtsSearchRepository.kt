package au.com.ausroads.offline.search

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import au.com.ausroads.core.common.PackRuntimeGenerationGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Singleton
class FtsSearchRepository @Inject constructor() : SearchRepository {

    private var db: SQLiteDatabase? = null
    private val _readiness = MutableStateFlow(false)
    override val readiness: StateFlow<Boolean> = _readiness.asStateFlow()

    override val isReady: Boolean
        get() = PackRuntimeGenerationGate.read { db?.isOpen == true }

    override fun open(dbPath: String) {
        val prepared = try {
            prepare(dbPath)
        } catch (error: SQLiteException) {
            Log.e(TAG, "Failed to open search DB: $dbPath", error)
            return
        }
        prepared.use(::activate)
    }

    /** Open and validate a candidate without disturbing the active database. */
    fun prepare(dbPath: String): PreparedSearchDatabase {
        val opened = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val rows = opened.validateSearchDatabase()
            Log.i(TAG, "Prepared search DB: $dbPath ($rows rows in search_meta)")
            return PreparedSearchDatabase(opened)
        } catch (error: RuntimeException) {
            opened.close()
            throw error
        }
    }

    /** Commit a validated candidate. The shared write lease makes this swap generation-atomic. */
    fun activate(prepared: PreparedSearchDatabase) {
        PackRuntimeGenerationGate.write {
            val replacement = prepared.claim()
            val previous = db
            db = replacement
            _readiness.value = true
            if (previous !== replacement) {
                runCatching { previous?.close() }
                    .onFailure { Log.w(TAG, "Closing replaced search DB failed", it) }
            }
        }
    }

    override fun close() {
        PackRuntimeGenerationGate.write {
            db?.close()
            db = null
            _readiness.value = false
        }
    }

    override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> =
        withContext(Dispatchers.IO) {
            PackRuntimeGenerationGate.read {
                val database = db ?: run {
                    Log.w(TAG, "search failed — no search DB is open")
                    throw SearchIndexUnavailableException("No search index is open")
                }
                val tokens = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                if (tokens.isEmpty()) return@read emptyList()
                val classPatterns = searchClassPatterns(query)

                // Substring match per token against the plain search_meta table. The pack
                // also carries an FTS5 index, but Android's system SQLite does NOT reliably
                // ship the fts5 module (observed "no such module: fts5" on a GrapheneOS
                // Pixel), so a MATCH query throws. LIKE over ~128k rows is comfortably fast
                // for a debounced search box and works on every device; shorter names first.
                val likeClauses = tokens.joinToString(" AND ") { "name LIKE ? ESCAPE '\\'" }
                val classClause = if (classPatterns.isNotEmpty()) {
                    classPatterns.joinToString(prefix = " OR ", separator = " OR ") { "class LIKE ?" }
                } else {
                    ""
                }
                val kindClause = if (kind != null) " AND kind = ?" else ""
                val sql =
                    """
                    SELECT name, kind, class, lat, lon
                    FROM search_meta
                    WHERE (($likeClauses)$classClause)$kindClause
                    ORDER BY length(name), name
                    LIMIT ?
                    """.trimIndent()
                val args = buildList {
                    tokens.forEach { add("%${escapeLike(it)}%") }
                    addAll(classPatterns)
                    if (kind != null) add(kind)
                    add(limit.toString())
                }.toTypedArray()

                val results = try {
                    database.rawQuery(sql, args).use { c -> c.toSearchResults() }
                } catch (e: SQLiteException) {
                    Log.e(TAG, "search query failed", e)
                    throw SearchIndexUnavailableException("The search index could not be queried", e)
                }
                Log.d(TAG, "search [${tokens.size} token(s)] -> ${results.size} results")
                results
            }
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
            PackRuntimeGenerationGate.read {
                val database = db ?: return@read null

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
        }

    override suspend fun browseByCategory(
        category: PoiCategory,
        limit: Int,
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            PackRuntimeGenerationGate.read {
                val database = db ?: return@read emptyList()
                if (category.classValues.isEmpty()) return@read emptyList()

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
        }

    override suspend fun nearestByCategory(
        latitude: Double,
        longitude: Double,
        category: PoiCategory,
        limit: Int,
        maxDistanceDegrees: Double,
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            PackRuntimeGenerationGate.read {
                val database = db ?: return@read emptyList()
                if (category.classValues.isEmpty()) return@read emptyList()

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
        }

    override suspend fun maxspeedNear(
        latitude: Double,
        longitude: Double,
        maxDistanceDegrees: Double,
    ): Int? =
        withContext(Dispatchers.IO) {
            PackRuntimeGenerationGate.read {
                val database = db ?: return@read null

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

    /**
     * Prove that a candidate can satisfy the actual name/category query contract
     * before it is allowed to replace the active generation. A row count alone
     * accepts old or malformed `search_meta` schemas and defers the failure until
     * after activation, when the previous working database has already closed.
     */
    private fun SQLiteDatabase.validateSearchDatabase(): Long {
        val rows = rawQuery("SELECT count(*) FROM search_meta", null).use { cursor ->
            if (!cursor.moveToFirst()) throw SQLiteException("search_meta count returned no row")
            cursor.getLong(0)
        }
        if (rows <= 0L) throw SQLiteException("search_meta is empty")

        val invalidRows = rawQuery(
            """
            SELECT count(*)
            FROM search_meta
            WHERE name IS NULL OR trim(name) = ''
               OR kind IS NULL OR trim(kind) = ''
               OR lat IS NULL OR lon IS NULL
               OR lat != lat OR lon != lon
               OR lat < -90.0 OR lat > 90.0
               OR lon < -180.0 OR lon > 180.0
            """.trimIndent(),
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw SQLiteException("search_meta validation returned no row")
            cursor.getLong(0)
        }
        if (invalidRows > 0L) {
            throw SQLiteException("search_meta contains $invalidRows unusable row(s)")
        }

        rawQuery(
            """
            SELECT name, kind, class, lat, lon
            FROM search_meta
            WHERE name LIKE ? ESCAPE '\' OR class LIKE ?
            ORDER BY length(name), name
            LIMIT 1
            """.trimIndent(),
            arrayOf("%", "%"),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw SQLiteException("search_meta representative query returned no row")
            val latitude = cursor.getDouble(3)
            val longitude = cursor.getDouble(4)
            if (!latitude.isFinite() || !longitude.isFinite()) {
                throw SQLiteException("search_meta representative coordinates are not finite")
            }
        }
        return rows
    }

    private companion object {
        private const val TAG = "FtsSearchRepository"
    }
}

/** Candidate database whose ownership transfers exactly once at activation. */
class PreparedSearchDatabase internal constructor(
    private var database: SQLiteDatabase?,
) : AutoCloseable {
    @Synchronized
    internal fun claim(): SQLiteDatabase = checkNotNull(database) {
        "Prepared search database was already activated or closed"
    }.also { database = null }

    @Synchronized
    override fun close() {
        database?.close()
        database = null
    }
}

class SearchIndexUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Friendly category words that users type even when those words are not in a POI's name. */
internal fun searchClassPatterns(query: String): List<String> = when (query.trim().lowercase()) {
    "supermarket", "supermarkets", "grocery", "groceries" -> listOf("shop=supermarket")
    "shop", "shops", "store", "stores" -> listOf("shop=%")
    "fuel", "petrol", "petrol station", "gas station" -> listOf("amenity=fuel")
    "pharmacy", "pharmacies", "chemist", "chemists" -> listOf("amenity=pharmacy")
    "hospital", "hospitals" -> listOf("amenity=hospital", "amenity=clinic")
    "police", "police station" -> listOf("amenity=police")
    "toilet", "toilets" -> listOf("amenity=toilets")
    "camping", "campground" -> listOf("tourism=camp_site", "tourism=caravan_site")
    else -> emptyList()
}
