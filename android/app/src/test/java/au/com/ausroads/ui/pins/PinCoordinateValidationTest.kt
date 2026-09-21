package au.com.ausroads.ui.pins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinCoordinateValidationTest {
    @Test
    fun `latitude accepts only finite values within world bounds`() {
        assertThat(isValidLatitude(-90.0)).isTrue()
        assertThat(isValidLatitude(90.0)).isTrue()
        assertThat(isValidLatitude(-90.01)).isFalse()
        assertThat(isValidLatitude(90.01)).isFalse()
        assertThat(isValidLatitude(Double.NaN)).isFalse()
        assertThat(isValidLatitude(Double.POSITIVE_INFINITY)).isFalse()
        assertThat(isValidLatitude(null)).isFalse()
    }

    @Test
    fun `longitude accepts only finite values within world bounds`() {
        assertThat(isValidLongitude(-180.0)).isTrue()
        assertThat(isValidLongitude(180.0)).isTrue()
        assertThat(isValidLongitude(-180.01)).isFalse()
        assertThat(isValidLongitude(180.01)).isFalse()
        assertThat(isValidLongitude(Double.NaN)).isFalse()
        assertThat(isValidLongitude(Double.NEGATIVE_INFINITY)).isFalse()
        assertThat(isValidLongitude(null)).isFalse()
    }
}
