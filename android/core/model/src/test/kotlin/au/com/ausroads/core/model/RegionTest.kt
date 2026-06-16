/*
 * Region.code is the canonical key for provider lookup, manifest region tagging, and
 * the database primary-key prefix. Lock its formatting rules here.
 */
package au.com.ausroads.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RegionTest {

    @Test
    fun `AU_SA code is AU-SA`() {
        assertThat(Region.AU_SA.code).isEqualTo("AU-SA")
    }

    @Test
    fun `AU_NSW code is AU-NSW`() {
        assertThat(Region.AU_NSW.code).isEqualTo("AU-NSW")
    }

    @Test
    fun `AU_NATIONAL has no state suffix`() {
        assertThat(Region.AU_NATIONAL.code).isEqualTo("AU")
    }

    @Test
    fun `Region with null state is just the country code uppercased`() {
        val region = Region(country = "AU", state = null)

        assertThat(region.code).isEqualTo("AU")
    }

    @Test
    fun `lowercase country and state are uppercased in code`() {
        val region = Region(country = "au", state = "sa")

        assertThat(region.code).isEqualTo("AU-SA")
    }

    @Test
    fun `mixed-case inputs are uppercased in code`() {
        val region = Region(country = "Au", state = "NsW")

        assertThat(region.code).isEqualTo("AU-NSW")
    }

    @Test
    fun `Regions with same fields are equal and hash equally`() {
        val a = Region(country = "AU", state = "sa")
        val b = Region(country = "AU", state = "sa")

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `Region with different state is not equal`() {
        assertThat(Region.AU_SA).isNotEqualTo(Region.AU_NSW)
    }

    @Test
    fun `Region with state and without state are not equal`() {
        assertThat(Region(country = "AU", state = "sa"))
            .isNotEqualTo(Region(country = "AU", state = null))
    }
}
