package au.com.ausroads.offline.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchAliasesTest {
    @Test
    fun `stores expands to all shop classes`() {
        assertThat(searchClassPatterns("stores")).containsExactly("shop=%")
    }

    @Test
    fun `supermarket expands to the OSM class emitted by the pack`() {
        assertThat(searchClassPatterns("Supermarket")).containsExactly("shop=supermarket")
    }
}
