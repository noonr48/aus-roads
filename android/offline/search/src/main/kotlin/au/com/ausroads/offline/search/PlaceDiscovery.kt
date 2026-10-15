package au.com.ausroads.offline.search

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlinx.coroutines.flow.StateFlow

/** Categories backed by actual OSM tags in the SA catalog, not inferred amenities. */
enum class ExploreCategory(val classes: Set<String>, val keywords: Set<String>) {
    COFFEE(
        setOf("amenity=cafe", "shop=bakery"),
        setOf("coffee", "cafe", "cafes", "bakery", "bakeries"),
    ),
    FOOD(
        setOf("amenity=restaurant", "amenity=fast_food", "amenity=pub", "amenity=bar"),
        setOf("food", "restaurant", "restaurants", "lunch", "dinner", "pub", "pubs", "bar", "bars"),
    ),
    NATURE(
        setOf("park", "leisure=park", "leisure=nature_reserve", "leisure=garden"),
        setOf("nature", "park", "parks", "garden", "gardens", "walk", "walks", "walking"),
    ),
    VIEWS(
        setOf("tourism=viewpoint", "tourism=attraction"),
        setOf(
            "view",
            "views",
            "viewpoint",
            "viewpoints",
            "lookout",
            "lookouts",
            "attraction",
            "attractions",
        ),
    ),
    CULTURE(
        setOf("tourism=museum", "tourism=gallery", "tourism=artwork"),
        setOf("culture", "museum", "museums", "gallery", "galleries", "art", "artwork"),
    ),
    PICNIC(setOf("tourism=picnic_site"), setOf("picnic", "picnics")),
    PLAY(
        setOf("leisure=playground", "leisure=sports_centre", "leisure=swimming_pool"),
        setOf("play", "playground", "playgrounds", "sport", "sports", "swimming"),
    ),
    CAMPING(
        setOf("tourism=camp_site", "tourism=caravan_site"),
        setOf("camp", "camping", "campsite", "campsites", "caravan", "caravans"),
    );

    companion object {
        fun forClass(value: String?): ExploreCategory? = entries.firstOrNull { value in it.classes }
    }
}

/** Explicit availability: an unavailable/corrupt index is never a successful empty search. */
enum class DiscoveryAvailability {
    LOADING,
    READY,
    UNAVAILABLE,
    FAILED,
}

class DiscoveryUnavailableException : IllegalStateException("Offline place catalog is unavailable")

interface PlaceDiscoveryRepository {
    val availability: StateFlow<DiscoveryAvailability>
    /** Changes on every open/close, including a replacement that remains READY. */
    val generation: StateFlow<Long>

    suspend fun discover(request: DiscoveryRequest): List<SearchResult>
}

data class DiscoveryRequest(
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Int = 20,
    val query: String = "",
    val category: ExploreCategory? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(radiusKm in 1..100)
        require(query.length <= MAX_QUERY_LENGTH)
    }

    companion object {
        const val MAX_QUERY_LENGTH = 120
        // Fetch independently per category, so dense cafés cannot truncate parks
        // out of the pool before interest/visit ranking. Not a global "best" claim.
        const val CANDIDATES_PER_CATEGORY = 60
    }
}

data class DiscoveryTerms(val names: List<String>, val categories: Set<ExploreCategory>) {
    companion object {
        fun parse(query: String): DiscoveryTerms {
            val categories = mutableSetOf<ExploreCategory>()
            val names = mutableListOf<String>()
            query
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
                .forEach { token ->
                    val normalized =
                        Normalizer.normalize(token, Normalizer.Form.NFD)
                            .replace(Regex("\\p{M}+"), "")
                            .lowercase(Locale.ROOT)
                    val category = ExploreCategory.entries.firstOrNull { normalized in it.keywords }
                    if (category == null) names += token else categories += category
                }
            return DiscoveryTerms(names, categories)
        }
    }
}

/** SQL and bindings are testable without Android; all data values are bound parameters. */
data class DiscoverySql(val sql: String, val args: Array<String>) {
    companion object {
        fun build(request: DiscoveryRequest, category: ExploreCategory): DiscoverySql {
            val terms = DiscoveryTerms.parse(request.query)
            val deltaLat =
                request.radiusKm / 110.0 // conservative enclosing box, not the radius test
            val extremeLatitude = (abs(request.latitude) + deltaLat).coerceAtMost(89.99)
            val deltaLon = (deltaLat / cos(Math.toRadians(extremeLatitude))).coerceAtMost(180.0)
            val lowLon = request.longitude - deltaLon
            val highLon = request.longitude + deltaLon
            val args = mutableListOf<String>()
            val clauses =
                mutableListOf("kind IN ('poi', 'park')", "lat IS NOT NULL", "lon IS NOT NULL")
            // rawQuery binds every argument as TEXT; against a column without REAL/INTEGER
            // affinity a bare TEXT bound never compares equal-or-in-range with a number, which
            // would turn a valid catalog into a silent successful-empty list. CAST fixes the type.
            clauses += "lat BETWEEN CAST(? AS REAL) AND CAST(? AS REAL)"
            args += (request.latitude - deltaLat).coerceAtLeast(-90.0).toString()
            args += (request.latitude + deltaLat).coerceAtMost(90.0).toString()
            when {
                deltaLon >= 180.0 -> Unit
                lowLon < -180.0 -> {
                    clauses += "(lon >= CAST(? AS REAL) OR lon <= CAST(? AS REAL))"
                    args += (lowLon + 360.0).toString()
                    args += highLon.toString()
                }
                highLon > 180.0 -> {
                    clauses += "(lon >= CAST(? AS REAL) OR lon <= CAST(? AS REAL))"
                    args += lowLon.toString()
                    args += (highLon - 360.0).toString()
                }
                else -> {
                    clauses += "lon BETWEEN CAST(? AS REAL) AND CAST(? AS REAL)"
                    args += lowLon.toString()
                    args += highLon.toString()
                }
            }
            clauses += "class IN (${category.classes.joinToString(",") { "?" }})"
            args += category.classes.sorted()
            terms.names.forEach {
                clauses += "name LIKE ? ESCAPE '\\'"
                args += "%${escape(it)}%"
            }
            // Rank the bounded candidates by an inexpensive planar approximation;
            // callers apply the actual great-circle radius before presenting any hit.
            val scale = cos(Math.toRadians(request.latitude))
            args += request.latitude.toString()
            args += request.latitude.toString()
            repeat(4) { args += request.longitude.toString() }
            args += scale.toString()
            args += scale.toString()
            args += DiscoveryRequest.CANDIDATES_PER_CATEGORY.toString()
            return DiscoverySql(
                "SELECT name, kind, class, lat, lon FROM search_meta WHERE ${clauses.joinToString(" AND ")} " +
                    "ORDER BY ((lat-?)*(lat-?))+(MIN(ABS(lon-?),360-ABS(lon-?))*" +
                    "MIN(ABS(lon-?),360-ABS(lon-?))*?*?), name, lat, lon LIMIT ?",
                args.toTypedArray(),
            )
        }

        private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

/**
 * No OSM ID exists in legacy packs: association uses normalized name/tag and ~1m coordinates.
 * Stable across row IDs/order; renamed or moved OSM places may need a new explicit visit.
 */
fun SearchResult.placeKey(): String = cachedPlaceKey

internal fun SearchResult.computePlaceKey(): String {
    val association =
        listOf(
                name.trim().lowercase(Locale.ROOT),
                kind,
                className.orEmpty(),
                String.format(Locale.ROOT, "%.5f", latitude),
                String.format(Locale.ROOT, "%.5f", longitude),
            )
            .joinToString("\u001f")
    val bytes = MessageDigest.getInstance("SHA-256").digest(association.toByteArray(Charsets.UTF_8))
    return "v1:" + bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
