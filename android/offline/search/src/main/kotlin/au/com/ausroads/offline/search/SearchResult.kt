package au.com.ausroads.offline.search

data class SearchResult(
    val name: String,
    val kind: String, // "suburb" | "poi" | "road" | "water" | "park"
    val className: String?, // OSM class (amenity type, place type, etc.)
    val latitude: Double,
    val longitude: Double,
) {
    // Owned immutable result metadata; copy() gets a fresh cache, equality stays field-only.
    internal val cachedPlaceKey: String by lazy { computePlaceKey() }
}
