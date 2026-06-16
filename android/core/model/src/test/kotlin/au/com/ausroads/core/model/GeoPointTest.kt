/*
 * GeoPoint is used by routing, traffic geometry, and pack manifests. Latitude/longitude
 * range errors are silent bugs (off-by-one in tile coords), so the validation is enforced
 * by init {} and we test it here.
 */
package au.com.ausroads.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeoPointTest {

    @Test
    fun `accepts a valid Adelaide point`() {
        val point = GeoPoint(longitude = 138.6, latitude = -34.9285)

        assertThat(point.longitude).isEqualTo(138.6)
        assertThat(point.latitude).isEqualTo(-34.9285)
    }

    @Test
    fun `accepts the equator and prime meridian`() {
        val origin = GeoPoint(longitude = 0.0, latitude = 0.0)

        assertThat(origin.longitude).isEqualTo(0.0)
        assertThat(origin.latitude).isEqualTo(0.0)
    }

    @Test
    fun `accepts the maximum and minimum valid coordinates`() {
        val max = GeoPoint(longitude = 180.0, latitude = 90.0)
        val min = GeoPoint(longitude = -180.0, latitude = -90.0)

        assertThat(max.longitude).isEqualTo(180.0)
        assertThat(max.latitude).isEqualTo(90.0)
        assertThat(min.longitude).isEqualTo(-180.0)
        assertThat(min.latitude).isEqualTo(-90.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects longitude above 180`() {
        GeoPoint(longitude = 180.1, latitude = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects longitude below -180`() {
        GeoPoint(longitude = -180.1, latitude = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects latitude above 90`() {
        GeoPoint(longitude = 0.0, latitude = 90.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects latitude below -90`() {
        GeoPoint(longitude = 0.0, latitude = -90.1)
    }

    @Test
    fun `error messages name the offending field`() {
        val ex = runCatching {
            GeoPoint(longitude = 500.0, latitude = 0.0)
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex).hasMessageThat().contains("longitude")
    }
}
