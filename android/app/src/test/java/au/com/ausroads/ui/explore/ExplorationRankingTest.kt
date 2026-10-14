package au.com.ausroads.ui.explore

import au.com.ausroads.offline.search.DiscoveryRequest
import au.com.ausroads.offline.search.ExploreCategory
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExplorationRankingTest {
    @Test
    fun `bbox corner beyond actual km radius is excluded`() {
        val inside = place("Inside", "amenity=cafe", 0.0, 0.01)
        val corner = place("Corner", "amenity=cafe", 0.044, 0.044)
        val ranked =
            ExplorationRanking.rank(
                listOf(corner, inside),
                DiscoveryRequest(0.0, 0.0, 5),
                ExplorationPreferences(),
            )
        assertThat(ranked.map { it.result.name }).containsExactly("Inside")
        assertThat(ranked.first().distanceMeters).isAtMost(5_000.0)
    }

    @Test
    fun `explicit interest changes ordering and truthful reason`() {
        val nearbyFood = place("Food", "amenity=restaurant", 0.0, 0.001)
        val coffee = place("Coffee", "amenity=cafe", 0.0, 0.01)
        val ranked =
            ExplorationRanking.rank(
                listOf(nearbyFood, coffee),
                request(),
                ExplorationPreferences(interests = setOf(ExploreCategory.COFFEE)),
            )
        assertThat(ranked.first().result).isEqualTo(coffee)
        assertThat(ranked.first().reason).isEqualTo(PlaceReason.INTEREST)
    }

    @Test
    fun `only explicitly recorded visits provide category affinity`() {
        val old = place("Old coffee", "amenity=cafe", 0.01, 0.01)
        val fresh = place("Fresh coffee", "amenity=cafe", 0.0, 0.001)
        val preferences =
            ExplorationPreferences(visits = mapOf(old.placeKey() to ExploreCategory.COFFEE))
        val result = ExplorationRanking.rank(listOf(fresh), request(), preferences).single()
        assertThat(result.reason).isEqualTo(PlaceReason.PREVIOUS_VISITS)
        assertThat(result.visited).isFalse()
        assertThat(preferences.visits).hasSize(1)
    }

    @Test
    fun `marked visits move behind new venues and undo restores novelty`() {
        val old = place("Old coffee", "amenity=cafe", 0.0, 0.001)
        val fresh = place("Fresh coffee", "amenity=cafe", 0.0, 0.01)
        val prefs = ExplorationPreferences(visits = mapOf(old.placeKey() to ExploreCategory.COFFEE))
        val ranked = ExplorationRanking.rank(listOf(old, fresh), request(), prefs)
        assertThat(ranked.first().result).isEqualTo(fresh)
        assertThat(ranked.last().reason).isEqualTo(PlaceReason.VISITED)
        val undone =
            ExplorationRanking.rank(listOf(old), request(), ExplorationPreferences()).single()
        assertThat(undone.visited).isFalse()
        assertThat(undone.reason).isEqualTo(PlaceReason.NEW_NEARBY)
    }

    @Test
    fun `cold start diversity is deterministic and duplicate associations collapse`() {
        val a = place("Coffee A", "amenity=cafe", 0.0, 0.001)
        val b = place("Coffee B", "amenity=cafe", 0.0, 0.002)
        val park = place("Park", "park", 0.0, 0.003)
        val first =
            ExplorationRanking.rank(
                listOf(b, a, a.copy(), park),
                request(),
                ExplorationPreferences(),
            )
        val second =
            ExplorationRanking.rank(listOf(park, a, b), request(), ExplorationPreferences())
        assertThat(first.map { it.result.name }).isEqualTo(second.map { it.result.name })
        assertThat(first).hasSize(3)
        assertThat(first.take(2).map { it.category }.distinct()).hasSize(2)
    }

    @Test
    fun `unknown classes cannot be invented into recommendations`() {
        assertThat(
                ExplorationRanking.rank(
                    listOf(place("Unclassified", "unknown", 0.0, 0.0)),
                    request(),
                    ExplorationPreferences(),
                )
            )
            .isEmpty()
    }

    private fun request() = DiscoveryRequest(0.0, 0.0, 20)

    private fun place(name: String, tag: String, lat: Double, lon: Double) =
        SearchResult(name, "poi", tag, lat, lon)
}
