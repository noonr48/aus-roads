package au.com.ausroads.offline.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure mapping checks for [PoiCategory]. These pin the OSM `class` tag values
 * each category resolves to, since they form the query contract against the
 * offline pack's `search_meta.class` column.
 */
class PoiCategoryTest {

    @Test
    fun `each category maps to its exact OSM class values`() {
        assertThat(PoiCategory.FUEL.classValues).containsExactly("amenity=fuel")
        assertThat(PoiCategory.EV_CHARGING.classValues)
            .containsExactly("amenity=charging_station")
        assertThat(PoiCategory.HOSPITAL.classValues)
            .containsExactly("amenity=hospital", "amenity=clinic")
        assertThat(PoiCategory.PHARMACY.classValues).containsExactly("amenity=pharmacy")
        assertThat(PoiCategory.POLICE.classValues).containsExactly("amenity=police")
        assertThat(PoiCategory.FIRE_STATION.classValues)
            .containsExactly("amenity=fire_station")
        assertThat(PoiCategory.TOILETS.classValues).containsExactly("amenity=toilets")
        assertThat(PoiCategory.DRINKING_WATER.classValues)
            .containsExactly("amenity=drinking_water")
        assertThat(PoiCategory.CAMPING.classValues)
            .containsExactly("tourism=camp_site", "tourism=caravan_site")
        assertThat(PoiCategory.SUPERMARKET.classValues).containsExactly("shop=supermarket")
    }

    @Test
    fun `every category exposes at least one class value`() {
        for (category in PoiCategory.entries) {
            assertThat(category.classValues).isNotEmpty()
        }
    }

    @Test
    fun `class values use the key equals value form`() {
        for (category in PoiCategory.entries) {
            for (value in category.classValues) {
                assertThat(value).contains("=")
            }
        }
    }

    @Test
    fun `class values are unique across all categories`() {
        val all = PoiCategory.entries.flatMap { it.classValues }
        assertThat(all).containsNoDuplicates()
    }
}
