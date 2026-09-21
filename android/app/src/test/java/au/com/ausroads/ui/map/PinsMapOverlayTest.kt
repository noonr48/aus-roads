package au.com.ausroads.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PinsMapOverlayTest {
    @Test
    fun `all selectable pin colours remain renderable`() {
        val selectableColors = listOf(
            "#1B5E20",
            "#00838F",
            "#6D4C41",
            "#C2185B",
            "#455A64",
            "#283593",
        )

        selectableColors.forEach { color ->
            assertThat(renderablePinColor(color)).isEqualTo(color)
        }
    }

    @Test
    fun `persisted colour is normalized before map matching`() {
        assertThat(renderablePinColor("  #c2185b  ")).isEqualTo("#C2185B")
    }

    @Test
    fun `blank malformed and unknown colours fall back to pin green`() {
        listOf("", "not-a-colour", "#FFFFFF", "#12345").forEach { color ->
            assertThat(renderablePinColor(color)).isEqualTo(PIN_FALLBACK_COLOR)
        }
    }
}
