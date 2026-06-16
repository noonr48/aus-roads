package au.com.ausroads.navigation.tts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavigationTtsTest {

    @Test
    fun `class exists`() {
        val clazz = NavigationTts::class.java
        assertThat(clazz).isNotNull()
    }

    @Test
    fun `speakText method exists`() {
        val method = NavigationTts::class.java.getDeclaredMethod("speakText", String::class.java)
        assertThat(method).isNotNull()
    }

    @Test
    fun `formatDistance returns meters for distances under 1000`() {
        assertThat(NavigationTts.formatDistance(500.0)).isEqualTo("In 500 meters")
    }

    @Test
    fun `formatDistance returns kilometers for distances at least 1000`() {
        assertThat(NavigationTts.formatDistance(1500.0)).isEqualTo("In 1 kilometers")
    }

    @Test
    fun `formatDistance returns kilometers for exactly 1000 meters`() {
        assertThat(NavigationTts.formatDistance(1000.0)).isEqualTo("In 1 kilometers")
    }

    @Test
    fun `formatDistance truncates fractional kilometers to integer`() {
        assertThat(NavigationTts.formatDistance(2750.0)).isEqualTo("In 2 kilometers")
    }

    @Test
    fun `formatDistance handles zero meters`() {
        assertThat(NavigationTts.formatDistance(0.0)).isEqualTo("In 0 meters")
    }

    @Test
    fun `formatDistance truncates fractional meters to integer`() {
        assertThat(NavigationTts.formatDistance(999.9)).isEqualTo("In 999 meters")
    }

    @Test
    fun `formatDistance handles large distances`() {
        assertThat(NavigationTts.formatDistance(12345.0)).isEqualTo("In 12 kilometers")
    }
}
