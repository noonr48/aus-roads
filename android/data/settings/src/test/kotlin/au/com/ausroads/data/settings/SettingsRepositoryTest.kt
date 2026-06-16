package au.com.ausroads.data.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import org.junit.Test

class SettingsRepositoryTest {

    private val repositoryClass = SettingsRepository::class.java

    @Test
    fun `interface has setTheme method`() {
        val method = repositoryClass.declaredMethods.firstOrNull { it.name == "setTheme" }

        assertThat(method).isNotNull()
        assertThat(method!!.parameterTypes).asList().contains(ThemeMode::class.java)
    }

    @Test
    fun `interface has setLiveTrafficEnabled method`() {
        val method = repositoryClass.declaredMethods.firstOrNull { it.name == "setLiveTrafficEnabled" }

        assertThat(method).isNotNull()
        // Suspend functions have an extra Continuation parameter at JVM level
        assertThat(method!!.parameterTypes).asList().contains(Boolean::class.java)
    }

    @Test
    fun `interface has setShowAttributionOverlay method`() {
        val method = repositoryClass.declaredMethods.firstOrNull { it.name == "setShowAttributionOverlay" }

        assertThat(method).isNotNull()
        assertThat(method!!.parameterTypes).asList().contains(Boolean::class.java)
    }

    @Test
    fun `interface has setTtsEnabled method`() {
        val method = repositoryClass.declaredMethods.firstOrNull { it.name == "setTtsEnabled" }

        assertThat(method).isNotNull()
        assertThat(method!!.parameterTypes).asList().contains(Boolean::class.java)
    }

    @Test
    fun `interface has setCongestionOverlayEnabled method`() {
        val method = repositoryClass.declaredMethods.firstOrNull { it.name == "setCongestionOverlayEnabled" }

        assertThat(method).isNotNull()
        assertThat(method!!.parameterTypes).asList().contains(Boolean::class.java)
    }

    @Test
    fun `interface has settings Flow property`() {
        val method = repositoryClass.declaredMethods.firstOrNull { it.name == "getSettings" }

        assertThat(method).isNotNull()
        assertThat(method!!.returnType).isEqualTo(Flow::class.java)
    }
}
