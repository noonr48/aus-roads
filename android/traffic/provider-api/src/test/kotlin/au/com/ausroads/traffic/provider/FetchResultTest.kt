package au.com.ausroads.traffic.provider

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration
import org.junit.Test

class FetchResultTest {

    @Test
    fun `Empty has no events`() {
        assertThat(FetchResult.Empty.events).isEmpty()
    }

    @Test
    fun `Empty has null serverTimestamp`() {
        assertThat(FetchResult.Empty.serverTimestamp).isNull()
    }

    @Test
    fun `Empty has zero cache max age`() {
        assertThat(FetchResult.Empty.cacheMaxAge).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `Empty has null etag`() {
        assertThat(FetchResult.Empty.etag).isNull()
    }

    @Test
    fun `Empty has null lastModified`() {
        assertThat(FetchResult.Empty.lastModified).isNull()
    }

    @Test
    fun `data class equality works`() {
        val a = FetchResult(
            events = emptyList(),
            serverTimestamp = null,
            cacheMaxAge = Duration.ZERO,
            etag = null,
            lastModified = null,
        )
        val b = FetchResult(
            events = emptyList(),
            serverTimestamp = null,
            cacheMaxAge = Duration.ZERO,
            etag = null,
            lastModified = null,
        )

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `data class inequality on different etag`() {
        val a = FetchResult.Empty.copy(etag = "abc")
        val b = FetchResult.Empty.copy(etag = "xyz")

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `Empty equals a manually constructed equivalent`() {
        val manual = FetchResult(
            events = emptyList(),
            serverTimestamp = null,
            cacheMaxAge = Duration.ZERO,
            etag = null,
            lastModified = null,
        )

        assertThat(FetchResult.Empty).isEqualTo(manual)
    }
}
