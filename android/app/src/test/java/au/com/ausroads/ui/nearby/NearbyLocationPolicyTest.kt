package au.com.ausroads.ui.nearby

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NearbyLocationPolicyTest {
    @Test
    fun `fresh fix after refresh request is accepted`() {
        assertThat(
            isFreshNearbyFix(
                fixTimeMillis = 1_005_000L,
                nowMillis = 1_006_000L,
                minimumFixTimeMillis = 1_000_000L,
                isPreciseLocationGranted = true,
            ),
        ).isTrue()
    }

    @Test
    fun `stale cached fix is rejected`() {
        assertThat(
            isFreshNearbyFix(
                fixTimeMillis = 700_000L,
                nowMillis = 1_000_000L,
                minimumFixTimeMillis = 0L,
                isPreciseLocationGranted = true,
            ),
        ).isFalse()
    }

    @Test
    fun `fix predating explicit refresh is rejected`() {
        assertThat(
            isFreshNearbyFix(
                fixTimeMillis = 999_000L,
                nowMillis = 1_001_000L,
                minimumFixTimeMillis = 1_000_000L,
                isPreciseLocationGranted = true,
            ),
        ).isFalse()
    }

    @Test
    fun `coarse fix remains usable across approximate callback cadence`() {
        assertThat(
            isFreshNearbyFix(
                fixTimeMillis = 400_000L,
                nowMillis = 1_000_000L,
                minimumFixTimeMillis = 0L,
                isPreciseLocationGranted = false,
            ),
        ).isTrue()
    }

    @Test
    fun `explicit refresh retains a still-fresh coarse fix`() {
        assertThat(
            isFreshNearbyFix(
                fixTimeMillis = 999_000L,
                nowMillis = 1_001_000L,
                minimumFixTimeMillis = 1_000_000L,
                isPreciseLocationGranted = false,
            ),
        ).isTrue()
    }

    @Test
    fun `coarse fix beyond approximate freshness window is rejected`() {
        assertThat(
            isFreshNearbyFix(
                fixTimeMillis = 99_999L,
                nowMillis = 1_000_000L,
                minimumFixTimeMillis = 0L,
                isPreciseLocationGranted = false,
            ),
        ).isFalse()
    }

    @Test
    fun `share text contains label coordinates and map link`() {
        val text = locationShareText(-34.9285, 138.6007, "Adelaide CBD")

        assertThat(text).contains("Adelaide CBD")
        assertThat(text).contains("-34.928500,138.600700")
        assertThat(text).contains("openstreetmap.org")
    }
}
