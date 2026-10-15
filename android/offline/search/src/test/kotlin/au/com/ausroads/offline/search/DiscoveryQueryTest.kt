package au.com.ausroads.offline.search

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DiscoveryQueryTest {
    @Test
    fun `coffee and accent cafe are real category keywords not name filters`() {
        assertThat(DiscoveryTerms.parse("coffee").categories)
            .containsExactly(ExploreCategory.COFFEE)
        assertThat(DiscoveryTerms.parse("Café").names).isEmpty()
        assertThat(DiscoveryTerms.parse("Café").categories).containsExactly(ExploreCategory.COFFEE)
    }

    @Test
    fun `remaining words stay bound name searches`() {
        val terms = DiscoveryTerms.parse("museum Adelaide")
        assertThat(terms.categories).containsExactly(ExploreCategory.CULTURE)
        assertThat(terms.names).containsExactly("Adelaide")
    }

    @Test
    fun `wildcards and apostrophes cannot widen or inject SQL`() {
        val query = DiscoverySql.build(request("%_' OR 1=1"), ExploreCategory.COFFEE)
        assertThat(query.sql).doesNotContain("OR 1=1")
        assertThat(query.args.toList()).contains("%\\%\\_'%")
        assertThat(query.sql.count { it == '?' }).isEqualTo(query.args.size)
        assertThat(query.args.toList()).contains("amenity=cafe")
    }

    @Test
    fun `each category has independent quota and conservative coordinate box`() {
        assertThat(ExploreCategory.NATURE.classes)
            .containsExactly("park", "leisure=park", "leisure=nature_reserve", "leisure=garden")
        assertThat(ExploreCategory.forClass("park")).isEqualTo(ExploreCategory.NATURE)
        assertThat(ExploreCategory.forClass("leisure=park")).isEqualTo(ExploreCategory.NATURE)
        val terms = DiscoveryTerms.parse("parks")
        assertThat(terms.categories).containsExactly(ExploreCategory.NATURE)
        assertThat(terms.names).isEmpty()
        val query = DiscoverySql.build(request("parks"), ExploreCategory.NATURE)
        val explicitNature =
            DiscoverySql.build(request().copy(category = ExploreCategory.NATURE), ExploreCategory.NATURE)
        assertThat(explicitNature.sql).isEqualTo(query.sql)
        assertThat(explicitNature.args.toList()).containsExactlyElementsIn(query.args.toList()).inOrder()
        assertThat(query.sql).contains("class IN (?,?,?,?)")
        assertThat(query.sql).doesNotContain("leisure=park")
        assertThat(query.sql).doesNotContain("name LIKE")
        assertThat(query.args.drop(4).take(4))
            .containsExactly("leisure=garden", "leisure=nature_reserve", "leisure=park", "park")
            .inOrder()
        assertThat(query.args.last()).isEqualTo("60")
        assertThat(query.sql).contains("kind IN ('poi', 'park')")
        assertThat(query.args.toList()).contains("leisure=nature_reserve")
        assertThat(query.args.toList()).contains("park")
        assertThat(query.sql.count { it == '?' }).isEqualTo(query.args.size)
    }

    @Test
    fun `invalid units coordinates and unbounded inputs fail before SQL`() {
        assertThrows(IllegalArgumentException::class.java) { DiscoveryRequest(Double.NaN, 138.6) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryRequest(-34.9, 181.0) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryRequest(-34.9, 138.6, 0) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryRequest(-34.9, 138.6, 101) }
        assertThrows(IllegalArgumentException::class.java) { request("x".repeat(121)) }
    }

    @Test
    fun `coordinate bounds are cast to REAL so untyped catalog columns still match`() {
        val query = DiscoverySql.build(request("parks"), ExploreCategory.NATURE)
        assertThat(query.sql).contains("lat BETWEEN CAST(? AS REAL) AND CAST(? AS REAL)")
        assertThat(query.sql).contains("lon BETWEEN CAST(? AS REAL) AND CAST(? AS REAL)")
        assertThat(query.sql.count { it == '?' }).isEqualTo(query.args.size)
    }

    @Test
    fun `dateline box has two longitude arms`() {
        val query = DiscoverySql.build(DiscoveryRequest(0.0, 179.95, 20), ExploreCategory.COFFEE)
        assertThat(query.sql).contains("(lon >= CAST(? AS REAL) OR lon <= CAST(? AS REAL))")
        assertThat(query.sql.count { it == '?' }).isEqualTo(query.args.size)
    }

    @Test
    fun `association is stable across name case and row identity but distinguishes places`() {
        val result = SearchResult("Cafe Test", "poi", "amenity=cafe", -34.9285, 138.6007)
        assertThat(result.placeKey()).isEqualTo(result.copy(name = " CAFE TEST ").placeKey())
        assertThat(result.placeKey()).isNotEqualTo(result.copy(longitude = 138.6017).placeKey())
        assertThat(result.placeKey())
            .isNotEqualTo(result.copy(className = "amenity=restaurant").placeKey())
        assertThat(result.placeKey()).startsWith("v1:")
        val legacyPark = result.copy(kind = "park", className = "park")
        val fullTagPark = legacyPark.copy(className = "leisure=park")
        assertThat(legacyPark.placeKey()).startsWith("v1:")
        assertThat(fullTagPark.placeKey()).startsWith("v1:")
        assertThat(fullTagPark.placeKey()).isNotEqualTo(legacyPark.placeKey())
    }

    private fun request(query: String = "") = DiscoveryRequest(-34.9285, 138.6007, query = query)
}
