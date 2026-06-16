package au.com.ausroads.core.common

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class AppDispatchersTest {

    private val dispatchers = AppDispatchers.Default

    @Test
    fun `Default object exists`() {
        assertThat(AppDispatchers.Default).isNotNull()
    }

    @Test
    fun `default property returns Dispatchers`() {
        assertThat(dispatchers.default).isEqualTo(Dispatchers.Default)
    }

    @Test
    fun `io property returns Dispatchers IO`() {
        assertThat(dispatchers.io).isEqualTo(Dispatchers.IO)
    }

    @Test
    fun `main property returns Dispatchers Main`() {
        assertThat(dispatchers.main).isEqualTo(Dispatchers.Main)
    }
}
