package au.com.ausroads.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CameraSearchVisibilityTest {
    @Test
    fun `blank and no-search baseline retains cameras but foreground query does not`() {
        assertThat(cameraSearchVisibility(true, false, "")).isTrue()
        assertThat(cameraSearchVisibility(true, false, " ")).isTrue()
        assertThat(cameraSearchVisibility(true, false, "Adelaide")).isFalse()
    }

    @Test
    fun `navigation overrides stale nonblank query but never overrides disabled setting`() {
        assertThat(cameraSearchVisibility(true, true, "Adelaide")).isTrue()
        assertThat(cameraSearchVisibility(false, true, "Adelaide")).isFalse()
        assertThat(cameraSearchVisibility(false, false, "")).isFalse()
    }
}
