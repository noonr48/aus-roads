package au.com.ausroads.data.pack

import au.com.ausroads.core.model.Region
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RegionConverterTest {

    private val converter = RegionConverter()

    @Test
    fun `round-trips AU-SA`() {
        val region = Region(country = "AU", state = "sa")

        val code = converter.fromRegion(region)
        val restored = converter.toRegion(code)

        assertThat(code).isEqualTo("AU-SA")
        assertThat(restored).isEqualTo(region)
    }

    @Test
    fun `round-trips AU-NSW`() {
        val region = Region(country = "AU", state = "nsw")

        val code = converter.fromRegion(region)
        val restored = converter.toRegion(code)

        assertThat(code).isEqualTo("AU-NSW")
        assertThat(restored).isEqualTo(region)
    }

    @Test
    fun `round-trips a country-only region`() {
        val region = Region(country = "AU")

        val code = converter.fromRegion(region)
        val restored = converter.toRegion(code)

        assertThat(code).isEqualTo("AU")
        assertThat(restored).isEqualTo(region)
    }

    @Test
    fun `fromRegion returns the canonical code`() {
        val region = Region(country = "AU", state = "VIC")

        assertThat(converter.fromRegion(region)).isEqualTo("AU-VIC")
    }

    @Test
    fun `toRegion parses a single-part code as country-only`() {
        val region = converter.toRegion("NZ")

        assertThat(region.country).isEqualTo("NZ")
        assertThat(region.state).isNull()
    }

    @Test
    fun `toRegion lowercases the state portion`() {
        val region = converter.toRegion("AU-QLD")

        assertThat(region.country).isEqualTo("AU")
        assertThat(region.state).isEqualTo("qld")
    }

    @Test
    fun `toRegion handles multi-dash code by splitting on first dash only`() {
        val region = converter.toRegion("XX-YY-ZZ")

        assertThat(region.country).isEqualTo("XX")
        assertThat(region.state).isEqualTo("yy-zz")
    }
}
