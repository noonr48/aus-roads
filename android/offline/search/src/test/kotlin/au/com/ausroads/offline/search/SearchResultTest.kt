package au.com.ausroads.offline.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchResultTest {

    @Test
    fun `equal results are equal`() {
        val a = SearchResult(
            name = "Ceduna",
            kind = "suburb",
            className = "place",
            latitude = -32.128,
            longitude = 133.677,
        )
        val b = SearchResult(
            name = "Ceduna",
            kind = "suburb",
            className = "place",
            latitude = -32.128,
            longitude = 133.677,
        )
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `different results are not equal`() {
        val a = SearchResult("Ceduna", "suburb", "place", -32.128, 133.677)
        val b = SearchResult("Port Augusta", "suburb", "place", -32.493, 137.764)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `className can be null`() {
        val result = SearchResult("Unnamed Road", "road", null, -30.0, 135.0)
        assertThat(result.className).isNull()
    }

    @Test
    fun `copy preserves equality`() {
        val original = SearchResult("Adelaide", "suburb", "place", -34.928, 138.601)
        val copy = original.copy()
        assertThat(copy).isEqualTo(original)
    }
}
