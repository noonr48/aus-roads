package au.com.ausroads.ui.map

import au.com.ausroads.offline.search.SearchResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchResultMapOverlayTest {
    @Test
    fun `poi selection zooms above ambient poi threshold`() {
        val result = SearchResult("Central Market", "poi", "shop=supermarket", -34.9, 138.6)

        assertThat(mapZoomForSearchResult(result)).isAtLeast(15.0)
    }

    @Test
    fun `suburb selection keeps regional context`() {
        val result = SearchResult("Adelaide", "suburb", "city", -34.9, 138.6)

        assertThat(mapZoomForSearchResult(result)).isEqualTo(13.0)
    }

    @Test
    fun `selected result converts to an actionable feature at its exact coordinate`() {
        val result = SearchResult("Central Market", "poi", "shop=supermarket", -34.929, 138.599)

        val feature = result.toIdentifiedFeature("Point of interest")

        assertThat(SELECTED_SEARCH_RESULT_POINT_LAYER_ID).isEqualTo("selected-search-result-point")
        assertThat(SELECTED_SEARCH_RESULT_HALO_LAYER_ID).isEqualTo("selected-search-result-halo")
        assertThat(feature.name).isEqualTo("Central Market")
        assertThat(feature.kind).isEqualTo("Point of interest")
        assertThat(feature.sourceLayer).isEqualTo("poi")
        assertThat(feature.featureClass).isEqualTo("shop=supermarket")
        assertThat(feature.latitude).isEqualTo(-34.929)
        assertThat(feature.longitude).isEqualTo(138.599)
    }

    @Test
    fun `selected result exposes both visible circle layers as hit targets`() {
        assertThat(
            selectedSearchResultHitLayerIds().toList(),
        ).containsExactly(
            "selected-search-result-point",
            "selected-search-result-halo",
        ).inOrder()
    }
}
