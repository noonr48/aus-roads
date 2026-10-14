package au.com.ausroads.ui.map

import au.com.ausroads.offline.search.SpeedCameraPoint
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class CameraFeatureIdentityTest {
    @Test fun markerAndTapIdentityKeepsLegacySixDecimalsInAnyDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val camera = SpeedCameraPoint(-34.92, 138.62, 60, "road")
            assertThat(camera.mapFeatureId()).isEqualTo("-34.920000,138.620000")
            assertThat(camera.copy(wayName = null, maxspeedKmh = null).mapFeatureId())
                .isEqualTo(camera.mapFeatureId())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
