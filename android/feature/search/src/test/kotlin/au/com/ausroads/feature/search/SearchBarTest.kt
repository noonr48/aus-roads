package au.com.ausroads.feature.search

import au.com.ausroads.offline.search.SearchResult
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class SearchBarTest {

    @Test
    fun `groupResultsByKind groups results by kind`() {
        val results = listOf(
            SearchResult("Adelaide", "suburb", null, -34.9, 138.6),
            SearchResult("Main St", "road", null, -34.9, 138.6),
            SearchResult("Rundle Mall", "poi", "shopping", -34.9, 138.6),
        )

        val grouped = groupResultsByKind(results)

        assertThat(grouped).hasSize(3)
        assertThat(grouped.map { it.first }).containsExactly("suburb", "road", "poi")
    }

    @Test
    fun `groupResultsByKind sorts by predefined kind order`() {
        val results = listOf(
            SearchResult("Lake", "water", null, -35.0, 138.5),
            SearchResult("Park", "park", null, -35.0, 138.5),
            SearchResult("Adelaide", "suburb", null, -34.9, 138.6),
            SearchResult("Highway", "road", null, -34.9, 138.6),
            SearchResult("Mall", "poi", "shopping", -34.9, 138.6),
        )

        val grouped = groupResultsByKind(results)

        assertThat(grouped.map { it.first }).containsExactly(
            "suburb", "road", "poi", "water", "park",
        ).inOrder()
    }

    @Test
    fun `groupResultsByKind puts unknown kinds at the end`() {
        val results = listOf(
            SearchResult("Unknown", "airfield", null, -35.0, 138.5),
            SearchResult("Adelaide", "suburb", null, -34.9, 138.6),
        )

        val grouped = groupResultsByKind(results)

        assertThat(grouped.last().first).isEqualTo("airfield")
    }

    @Test
    fun `groupResultsByKind returns empty list for empty input`() {
        val grouped = groupResultsByKind(emptyList())
        assertThat(grouped).isEmpty()
    }

    @Test
    fun `groupResultsByKind preserves all results within each group`() {
        val results = listOf(
            SearchResult("Adelaide", "suburb", null, -34.9, 138.6),
            SearchResult("Prospect", "suburb", null, -34.88, 138.59),
            SearchResult("Main St", "road", null, -34.9, 138.6),
        )

        val grouped = groupResultsByKind(results)

        val suburbGroup = grouped.first { it.first == "suburb" }
        assertThat(suburbGroup.second).hasSize(2)
    }

    @Test
    fun `result subtitles distinguish repeated names by coordinates`() {
        val first = SearchResult("IGA", "poi", "shop", -34.9285, 138.6007)
        val second = SearchResult("IGA", "poi", "shop", -34.9012, 138.6123)

        assertThat(searchResultSubtitle(first)).isEqualTo(
            "Poi \u00b7 shop \u00b7 34.9285\u00b0 S, 138.6007\u00b0 E",
        )
        assertThat(searchResultSubtitle(second)).isNotEqualTo(searchResultSubtitle(first))
    }
}
