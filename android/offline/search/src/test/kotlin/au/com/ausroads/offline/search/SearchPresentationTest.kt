package au.com.ausroads.offline.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchPresentationTest {
    @Test
    fun `known kinds use friendly singular and group labels`() {
        assertThat(SearchPresentation.kindLabel("poi")).isEqualTo("Point of interest")
        assertThat(SearchPresentation.groupLabel("poi")).isEqualTo("Points of interest")
        assertThat(SearchPresentation.kindLabel("suburb")).isEqualTo("Place")
        assertThat(SearchPresentation.groupLabel("suburb")).isEqualTo("Places")
        assertThat(SearchPresentation.kindLabel("water")).isEqualTo("Water feature")
        assertThat(SearchPresentation.groupLabel("water")).isEqualTo("Water features")
    }

    @Test
    fun `unknown kinds are humanized without exposing storage formatting`() {
        assertThat(SearchPresentation.kindLabel("railway_station")).isEqualTo("Railway station")
        assertThat(SearchPresentation.groupLabel("airfield")).isEqualTo("Airfields")
        assertThat(SearchPresentation.kindLabel("  ")).isEqualTo("Other")
        assertThat(SearchPresentation.groupLabel("  ")).isEqualTo("Other")
    }

    @Test
    fun `OSM key value classes expose only a friendly value`() {
        assertThat(SearchPresentation.classLabel("shop=supermarket")).isEqualTo("Supermarket")
        assertThat(SearchPresentation.classLabel("amenity=place_of_worship"))
            .isEqualTo("Place of worship")
        assertThat(SearchPresentation.classLabel("tourism=caravan_site"))
            .isEqualTo("Caravan site")
    }

    @Test
    fun `plain classes are humanized`() {
        assertThat(SearchPresentation.classLabel("MOTORWAY_LINK")).isEqualTo("Motorway link")
        assertThat(SearchPresentation.classLabel("fire-station")).isEqualTo("Fire station")
    }

    @Test
    fun `missing or empty classes stay absent`() {
        assertThat(SearchPresentation.classLabel(null)).isNull()
        assertThat(SearchPresentation.classLabel("  ")).isNull()
        assertThat(SearchPresentation.classLabel("amenity=")).isNull()
    }

    @Test
    fun `boolean OSM tag values stay absent instead of becoming meaningless labels`() {
        assertThat(SearchPresentation.classLabel("shop=yes")).isNull()
        assertThat(SearchPresentation.classLabel("amenity=TRUE")).isNull()
        assertThat(SearchPresentation.classLabel("access=0")).isNull()
        assertThat(SearchPresentation.classLabel("amenity=unknown")).isNull()
    }
}
