package au.com.ausroads.ui.map

import android.Manifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocationPermissionStateTest {
    @Test
    fun `approximate location grant is accepted`() {
        val grants = mapOf(
            Manifest.permission.ACCESS_COARSE_LOCATION to true,
            Manifest.permission.ACCESS_FINE_LOCATION to false,
        )

        assertThat(grants.hasForegroundLocationGrant()).isTrue()
        assertThat(grants.hasPreciseLocationGrant()).isFalse()
    }

    @Test
    fun `denying both foreground permissions is rejected`() {
        val grants = ForegroundLocationPermissions.associateWith { false }

        assertThat(grants.hasForegroundLocationGrant()).isFalse()
        assertThat(grants.hasPreciseLocationGrant()).isFalse()
    }

    @Test
    fun `fine foreground grant is precise`() {
        val grants = mapOf(
            Manifest.permission.ACCESS_COARSE_LOCATION to true,
            Manifest.permission.ACCESS_FINE_LOCATION to true,
        )

        assertThat(grants.hasForegroundLocationGrant()).isTrue()
        assertThat(grants.hasPreciseLocationGrant()).isTrue()
    }
}
