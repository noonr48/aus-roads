package au.com.ausroads.ui.explore

import au.com.ausroads.core.geo.MeasureGeometry
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.offline.search.DiscoveryRequest
import au.com.ausroads.offline.search.ExploreCategory
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey

enum class PlaceReason {
    KEYWORD,
    INTEREST,
    PREVIOUS_VISITS,
    NEW_NEARBY,
    NEARBY,
    VISITED,
}

data class SuggestedPlace(
    val result: SearchResult,
    val category: ExploreCategory,
    val distanceMeters: Double,
    val visited: Boolean,
    val reason: PlaceReason,
    val score: Double,
)

/** Deterministic local ranking; never a rating, opening-hours or venue-quality predictor. */
object ExplorationRanking {
    private const val MAX_RESULTS = 30
    private const val INTEREST_BONUS = 24.0
    private const val NEW_BONUS = 12.0
    private const val VISITED_PENALTY = 24.0
    private const val MAX_VISIT_AFFINITY = 3
    private const val VISIT_AFFINITY_BONUS = 4.0
    private const val DISTANCE_PENALTY = 20.0
    private const val DIVERSITY_MARGIN = 12.0

    fun rank(
        candidates: List<SearchResult>,
        request: DiscoveryRequest,
        preferences: ExplorationPreferences,
    ): List<SuggestedPlace> {
        val context =
            RankContext(
                GeoPoint(longitude = request.longitude, latitude = request.latitude),
                request.radiusKm * 1_000.0,
                preferences,
                request.query.isNotBlank(),
                preferences.visits.values.groupingBy { it }.eachCount(),
            )
        val ranked =
            candidates
                .distinctBy { it.placeKey() }
                .mapNotNull { suggest(it, context) }
                .sortedWith(
                    compareByDescending<SuggestedPlace> { it.score }
                        .thenBy { it.distanceMeters }
                        .thenBy { it.result.placeKey() }
                )
        return if (context.keyword || request.category != null) ranked.take(MAX_RESULTS)
        else diversify(ranked)
    }

    private fun suggest(result: SearchResult, context: RankContext): SuggestedPlace? {
        val category = ExploreCategory.forClass(result.className) ?: return null
        val distance =
            MeasureGeometry.haversineMeters(
                context.reference,
                GeoPoint(longitude = result.longitude, latitude = result.latitude),
            )
        // Acquisition uses a bbox; the actual circle is this great-circle test.
        if (!distance.isFinite() || distance > context.radiusMeters) return null
        val visited = result.placeKey() in context.preferences.visits
        val interested = category in context.preferences.interests
        val affinity = (context.affinity[category] ?: 0).coerceAtMost(MAX_VISIT_AFFINITY)
        return SuggestedPlace(
            result,
            category,
            distance,
            visited,
            reason(context, visited, interested, affinity),
            score(context, visited, interested, affinity, distance),
        )
    }

    private fun score(
        context: RankContext,
        visited: Boolean,
        interested: Boolean,
        affinity: Int,
        distance: Double,
    ): Double =
        (if (interested) INTEREST_BONUS else 0.0) +
            affinity * VISIT_AFFINITY_BONUS +
            (if (context.preferences.preferNew && !visited) NEW_BONUS else 0.0) -
            (if (context.preferences.preferNew && visited) VISITED_PENALTY else 0.0) -
            (distance / context.radiusMeters) * DISTANCE_PENALTY

    private fun reason(
        context: RankContext,
        visited: Boolean,
        interested: Boolean,
        affinity: Int,
    ): PlaceReason =
        when {
            visited -> PlaceReason.VISITED
            context.keyword -> PlaceReason.KEYWORD
            interested -> PlaceReason.INTEREST
            affinity > 0 -> PlaceReason.PREVIOUS_VISITS
            context.preferences.preferNew -> PlaceReason.NEW_NEARBY
            else -> PlaceReason.NEARBY
        }

    private fun diversify(ranked: List<SuggestedPlace>): List<SuggestedPlace> {
        val remaining = ranked.toMutableList()
        val selected = mutableListOf<SuggestedPlace>()
        while (remaining.isNotEmpty() && selected.size < MAX_RESULTS) {
            val best = remaining.first()
            val previous = selected.lastOrNull()?.category
            val alternate = remaining.indexOfFirst {
                it.category != previous && it.score >= best.score - DIVERSITY_MARGIN
            }
            selected += remaining.removeAt(if (alternate >= 0) alternate else 0)
        }
        return selected
    }

    private data class RankContext(
        val reference: GeoPoint,
        val radiusMeters: Double,
        val preferences: ExplorationPreferences,
        val keyword: Boolean,
        val affinity: Map<ExploreCategory, Int>,
    )
}
