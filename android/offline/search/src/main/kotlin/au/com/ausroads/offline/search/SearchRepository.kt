package au.com.ausroads.offline.search

interface SearchRepository {
    /**
     * Search for features matching the given query.
     * Uses FTS5 prefix matching: "cedu" matches "Ceduna", "Ceduna 5690", etc.
     * @param query The search text (partial or full)
     * @param limit Maximum number of results to return
     * @param kind Optional kind filter ("suburb", "poi", "road", etc.)
     */
    suspend fun search(query: String, limit: Int = 20, kind: String? = null): List<SearchResult>

    /**
     * Reverse-geocode a coordinate to the nearest indexed feature (offline).
     *
     * Used to label a dropped pin with a human-readable place name instead of
     * raw coordinates. Returns the closest [SearchResult] by planar distance, or
     * null when the index is empty/closed or nothing is within [maxDistanceDegrees].
     *
     * @param kind Optional kind filter, e.g. "suburb" to prefer suburb names.
     */
    suspend fun nearest(
        latitude: Double,
        longitude: Double,
        kind: String? = null,
        maxDistanceDegrees: Double = 0.25,
    ): SearchResult?

    /**
     * List every indexed POI in the given [category], ordered alphabetically by
     * name (offline). Used to populate a "find a [category]" browse list.
     *
     * Filters `search_meta.class` to the category's [PoiCategory.classValues].
     *
     * @param category The POI category to list.
     * @param limit Maximum number of results to return.
     * @return Matching POIs sorted by name, or empty when the index is
     *   closed/empty or nothing matches.
     */
    suspend fun browseByCategory(category: PoiCategory, limit: Int = 200): List<SearchResult>

    /**
     * Find the POIs in [category] nearest to the given coordinate (offline),
     * closest first. Used for "nearest fuel / hospital / toilets" lookups.
     *
     * Mirrors [nearest]: a bbox pre-filter plus cos-lat-scaled squared planar
     * distance, additionally filtered to the category's
     * [PoiCategory.classValues].
     *
     * @param limit Maximum number of results to return.
     * @param maxDistanceDegrees Half-width of the search bbox in degrees.
     * @return Up to [limit] matching POIs ordered nearest-first, or empty when
     *   the index is closed/empty or nothing is within range.
     */
    suspend fun nearestByCategory(
        latitude: Double,
        longitude: Double,
        category: PoiCategory,
        limit: Int = 20,
        maxDistanceDegrees: Double = 0.5,
    ): List<SearchResult>

    /**
     * Reverse-look-up the posted speed limit (km/h) of the road nearest to the
     * given coordinate (offline), or null when none is known.
     *
     * Queries the optional `road_speed` table by cos-lat-scaled squared planar
     * distance. Packs built before the speed layer existed have no `road_speed`
     * table; this method tolerates that and returns null rather than throwing.
     *
     * @param maxDistanceDegrees Half-width of the search bbox in degrees.
     * @return The nearest road's `maxspeed_kmh`, or null when the table is
     *   absent, the index is closed, or nothing is within range.
     */
    suspend fun maxspeedNear(
        latitude: Double,
        longitude: Double,
        maxDistanceDegrees: Double = 0.05,
    ): Int?

    /**
     * Open the search database from the given file path.
     * Must be called before search() will return results.
     */
    fun open(dbPath: String)

    /**
     * Close the database connection.
     */
    fun close()
}
