package au.com.ausroads.offline.search

data class SearchResult(
    val name: String,
    val kind: String,       // "suburb" | "poi" | "road" | "water" | "park"
    val className: String?, // OSM class (amenity type, place type, etc.)
    val latitude: Double,
    val longitude: Double,
)
