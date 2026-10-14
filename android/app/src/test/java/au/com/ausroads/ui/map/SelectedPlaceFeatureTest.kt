package au.com.ausroads.ui.map

import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.maplibre.geojson.Point

class SelectedPlaceFeatureTest {
    private val place = SearchResult("33 Chapel", "poi", "amenity=cafe", -34.9165237, 138.6253255)

    @Test fun sourceInputKeepsExactCatalogLongitudeLatitudeAndAssociation() {
        val feature = selectedPlaceFeature(place)
        val point = feature.geometry() as Point
        assertThat(point.longitude()).isEqualTo(place.longitude)
        assertThat(point.latitude()).isEqualTo(place.latitude)
        assertThat(feature.getStringProperty(SELECTED_PLACE_KEY)).isEqualTo(place.placeKey())
    }

    @Test fun newSelectionChangesGeometryAndIdentityWithoutMutatingEarlierFeature() {
        val earlier = selectedPlaceFeature(place)
        val next = place.copy(name = "Another cafe", latitude = -34.925, longitude = 138.635)
        val replacement = selectedPlaceFeature(next)
        val point = replacement.geometry() as Point
        assertThat(point.longitude()).isEqualTo(next.longitude)
        assertThat(point.latitude()).isEqualTo(next.latitude)
        assertThat(replacement.getStringProperty(SELECTED_PLACE_KEY)).isEqualTo(next.placeKey())
        assertThat(replacement.getStringProperty(SELECTED_PLACE_KEY))
            .isNotEqualTo(earlier.getStringProperty(SELECTED_PLACE_KEY))
        assertThat((earlier.geometry() as Point).longitude()).isEqualTo(place.longitude)
        assertThat((earlier.geometry() as Point).latitude()).isEqualTo(place.latitude)
    }
}
