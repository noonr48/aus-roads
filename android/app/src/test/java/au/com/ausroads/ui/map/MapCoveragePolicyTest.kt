package au.com.ausroads.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapCoveragePolicyTest {
    @Test
    fun `bundled map accepts Adelaide and rejects regional South Australia`() {
        assertThat(
            isInsideActiveMapCoverage(
                usingBundledFallback = true,
                latitude = -34.9285,
                longitude = 138.6007,
            ),
        ).isTrue()
        assertThat(
            isInsideActiveMapCoverage(
                usingBundledFallback = true,
                latitude = -32.4921,
                longitude = 137.7650,
            ),
        ).isFalse()
    }

    @Test
    fun `installed state pack accepts regional South Australia and rejects interstate`() {
        assertThat(
            isInsideActiveMapCoverage(
                usingBundledFallback = false,
                latitude = -32.4921,
                longitude = 137.7650,
            ),
        ).isTrue()
        assertThat(
            isInsideActiveMapCoverage(
                usingBundledFallback = false,
                latitude = -37.8136,
                longitude = 144.9631,
            ),
        ).isFalse()
    }
}
