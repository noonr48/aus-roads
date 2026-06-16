/*
 * The stub provider is what the v0.1 app ships with: a no-op that the consumer can rely
 * on to be present, but that never returns traffic. Lock its identity and emptiness so
 * the consumer layer can be unit-tested against it without a network.
 */
package au.com.ausroads.traffic.provider.stub

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.traffic.provider.FetchResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StubLiveTrafficProviderTest {

    @Test
    fun `regionCode is STUB`() {
        val provider = StubLiveTrafficProvider()

        assertThat(provider.regionCode).isEqualTo("STUB")
    }

    @Test
    fun `displayName is the stub label`() {
        val provider = StubLiveTrafficProvider()

        assertThat(provider.displayName).isEqualTo("Stub provider (no traffic)")
    }

    @Test
    fun `supportedBbox is the global bbox`() {
        val provider = StubLiveTrafficProvider()

        assertThat(provider.supportedBbox).isEqualTo(
            Bbox(west = -180.0, south = -90.0, east = 180.0, north = 90.0),
        )
    }

    @Test
    fun `supportsChangeTracking is false`() {
        val provider = StubLiveTrafficProvider()

        assertThat(provider.supportsChangeTracking()).isFalse()
    }

    @Test
    fun `fetchEvents returns FetchResult Empty`() = runBlocking {
        val provider = StubLiveTrafficProvider()

        val result = provider.fetchEvents(bbox = null)

        assertThat(result).isEqualTo(FetchResult.Empty)
    }

    @Test
    fun `fetchEvents ignores a provided bbox and still returns Empty`() = runBlocking {
        val provider = StubLiveTrafficProvider()
        val adelaide = Bbox(west = 138.4, south = -35.2, east = 139.0, north = -34.6)

        val result = provider.fetchEvents(bbox = adelaide, ifNoneMatch = "etag-1")

        assertThat(result).isEqualTo(FetchResult.Empty)
    }

    @Test
    fun `fetchClosures returns FetchResult Empty`() = runBlocking {
        val provider = StubLiveTrafficProvider()

        val result = provider.fetchClosures(bbox = null)

        assertThat(result).isEqualTo(FetchResult.Empty)
    }

    @Test
    fun `FetchResult Empty has empty events and null etag`() {
        val empty = FetchResult.Empty

        assertThat(empty.events).isEmpty()
        assertThat(empty.etag).isNull()
        assertThat(empty.serverTimestamp).isNull()
        assertThat(empty.lastModified).isNull()
    }
}
