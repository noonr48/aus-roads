package au.com.ausroads.data.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsTest {

    @Test
    fun `default settings have System theme`() {
        val settings = Settings()

        assertThat(settings.theme).isEqualTo(ThemeMode.System)
    }

    @Test
    fun `default settings show attribution overlay`() {
        val settings = Settings()

        assertThat(settings.showAttributionOverlay).isTrue()
    }

    @Test
    fun `default settings have live traffic disabled`() {
        val settings = Settings()

        assertThat(settings.liveTrafficEnabled).isFalse()
    }

    @Test
    fun `ThemeMode valueOf works for all values`() {
        assertThat(ThemeMode.valueOf("System")).isEqualTo(ThemeMode.System)
        assertThat(ThemeMode.valueOf("Light")).isEqualTo(ThemeMode.Light)
        assertThat(ThemeMode.valueOf("Dark")).isEqualTo(ThemeMode.Dark)
    }

    @Test
    fun `ThemeMode has exactly three values`() {
        assertThat(ThemeMode.entries).hasSize(3)
    }

    @Test
    fun `data class equality works`() {
        val a = Settings(theme = ThemeMode.Dark, showAttributionOverlay = false, liveTrafficEnabled = true)
        val b = Settings(theme = ThemeMode.Dark, showAttributionOverlay = false, liveTrafficEnabled = true)

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `data class inequality on different theme`() {
        val a = Settings(theme = ThemeMode.Light)
        val b = Settings(theme = ThemeMode.Dark)

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `copy overrides only specified fields`() {
        val original = Settings()
        val copied = original.copy(theme = ThemeMode.Dark)

        assertThat(copied.theme).isEqualTo(ThemeMode.Dark)
        assertThat(copied.showAttributionOverlay).isTrue()
        assertThat(copied.liveTrafficEnabled).isFalse()
    }
}
