package au.com.ausroads.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserLocationOverlayTest {
    @Test
    fun `approximate accuracy renders a visibly broad halo`() {
        val radius = locationAccuracyHaloRadiusPixels(
            accuracyMeters = 2_000f,
            latitude = -34.9285,
            zoom = 12.0,
            isPreciseLocationGranted = false,
        )

        assertThat(radius).isGreaterThan(50f)
    }

    @Test
    fun `accuracy halo grows as the map zooms in`() {
        val zoomedOut = locationAccuracyHaloRadiusPixels(
            accuracyMeters = 500f,
            latitude = -34.9285,
            zoom = 12.0,
            isPreciseLocationGranted = true,
        )
        val zoomedIn = locationAccuracyHaloRadiusPixels(
            accuracyMeters = 500f,
            latitude = -34.9285,
            zoom = 15.0,
            isPreciseLocationGranted = true,
        )

        assertThat(zoomedIn).isGreaterThan(zoomedOut)
    }

    @Test
    fun `missing precise accuracy keeps the conventional minimum halo`() {
        val radius = locationAccuracyHaloRadiusPixels(
            accuracyMeters = null,
            latitude = -34.9285,
            zoom = 14.0,
            isPreciseLocationGranted = true,
        )

        assertThat(radius).isEqualTo(18f)
    }

    @Test
    fun `missing approximate accuracy still avoids false precision`() {
        val radius = locationAccuracyHaloRadiusPixels(
            accuracyMeters = null,
            latitude = -34.9285,
            zoom = 12.0,
            isPreciseLocationGranted = false,
        )

        assertThat(radius).isGreaterThan(50f)
    }

    @Test
    fun `extreme accuracy is bounded to a usable on-screen halo`() {
        val radius = locationAccuracyHaloRadiusPixels(
            accuracyMeters = Float.MAX_VALUE,
            latitude = 90.0,
            zoom = 24.0,
            isPreciseLocationGranted = false,
        )

        assertThat(radius).isEqualTo(120f)
    }
}
