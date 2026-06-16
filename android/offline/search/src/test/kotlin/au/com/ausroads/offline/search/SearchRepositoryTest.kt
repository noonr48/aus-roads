package au.com.ausroads.offline.search

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchRepositoryTest {

    private class FakeSearchRepository : SearchRepository {
        var results: List<SearchResult> = emptyList()
        private var isOpen = false

        override fun open(dbPath: String) {
            isOpen = true
        }

        override fun close() {
            isOpen = false
        }

        override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> {
            return results.take(limit)
        }

        override suspend fun nearest(
            latitude: Double,
            longitude: Double,
            kind: String?,
            maxDistanceDegrees: Double,
        ): SearchResult? = results.firstOrNull()

        override suspend fun browseByCategory(
            category: PoiCategory,
            limit: Int,
        ): List<SearchResult> =
            results.filter { it.className in category.classValues }.take(limit)

        override suspend fun nearestByCategory(
            latitude: Double,
            longitude: Double,
            category: PoiCategory,
            limit: Int,
            maxDistanceDegrees: Double,
        ): List<SearchResult> =
            results.filter { it.className in category.classValues }.take(limit)

        override suspend fun maxspeedNear(
            latitude: Double,
            longitude: Double,
            maxDistanceDegrees: Double,
        ): Int? = null
    }

    @Test
    fun `SearchRepository has search suspend function`() = runTest {
        val repo: SearchRepository = FakeSearchRepository()
        repo.open("fake.db")
        val results = repo.search("test", 20, null)
        assertThat(results).isEmpty()
    }

    @Test
    fun `SearchRepository search returns List of SearchResult`() = runTest {
        val repo = FakeSearchRepository()
        val expected = listOf(
            SearchResult("Ceduna", "suburb", "place", -32.128, 133.677),
            SearchResult("Ceduna 5690", "suburb", "place", -32.130, 133.680),
        )
        repo.results = expected

        val results: List<SearchResult> = repo.search("cedu", 20, null)

        assertThat(results).hasSize(2)
        assertThat(results).isEqualTo(expected)
    }

    @Test
    fun `SearchResult fields are accessible`() {
        val result = SearchResult(
            name = "Adelaide",
            kind = "suburb",
            className = "place",
            latitude = -34.928,
            longitude = 138.601,
        )
        assertThat(result.name).isEqualTo("Adelaide")
        assertThat(result.kind).isEqualTo("suburb")
        assertThat(result.className).isEqualTo("place")
        assertThat(result.latitude).isWithin(0.001).of(-34.928)
        assertThat(result.longitude).isWithin(0.001).of(138.601)
    }

    @Test
    fun `SearchResult equality works correctly`() {
        val a = SearchResult("Ceduna", "suburb", "place", -32.128, 133.677)
        val b = SearchResult("Ceduna", "suburb", "place", -32.128, 133.677)
        val c = SearchResult("Port Augusta", "suburb", "place", -32.493, 137.764)

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(c)
    }
}
