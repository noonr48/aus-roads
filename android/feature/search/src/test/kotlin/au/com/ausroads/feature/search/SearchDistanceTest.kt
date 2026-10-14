package au.com.ausroads.feature.search

import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.offline.search.SearchResult
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test

class SearchDistanceTest {
    private val reference = GeoPoint(longitude = 138.62, latitude = -34.92)
    private val result = SearchResult("Adelaide Road", "road", "primary", -34.92, 138.62)
    private lateinit var previousLocale: Locale

    @Before
    fun locale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `display uses real map-area coordinates and meters or kilometers`() {
        assertThat(mapDistanceLabel(result, reference)).isEqualTo("0 m")
        assertThat(mapDistanceLabel(result.copy(longitude = 138.621), reference)).isEqualTo("91 m")
        assertThat(mapDistanceLabel(result.copy(latitude = -34.91), reference)).isEqualTo("1.1 km")
    }

    @Test
    fun `same names at different coordinates get distinct distance cues`() {
        val near = mapDistanceLabel(result.copy(latitude = -34.91), reference)
        val far = mapDistanceLabel(result.copy(latitude = -33.91), reference)
        assertThat(near).isNotEqualTo(far)
        assertThat(result.name).isEqualTo("Adelaide Road")
    }

    @Test
    fun `unavailable reference or invalid catalog coordinates cannot invent a distance`() {
        assertThat(mapDistanceLabel(result, null)).isNull()
        assertThat(mapDistanceLabel(result.copy(latitude = Double.NaN), reference)).isNull()
        assertThat(mapDistanceLabel(result.copy(longitude = 181.0), reference)).isNull()
    }
}
