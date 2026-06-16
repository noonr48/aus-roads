package au.com.ausroads.offline.download.network

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The HostAllowlist is the privacy audit's endpoint gate: even if a client is
 * constructed, requests to non-allowlisted hosts must be rejected at runtime.
 * These tests lock that contract so a future change can't silently widen it.
 */
class HostAllowlistTest {

    private fun client(extraHosts: Set<String> = emptySet()) =
        HttpClient(MockEngine) {
            install(hostAllowlistPlugin(extraHosts))
            engine { addHandler { respond("ok", HttpStatusCode.OK) } }
        }

    @Test
    fun `allows the GitHub pack-hosting hosts`() = runTest {
        // Must not throw — GitHub release URL + its redirect target + raw.
        client().get("https://github.com/noonr48/aus-roads/releases/download/sa-pack/latest.json")
        client().get("https://release-assets.githubusercontent.com/x/y/pack.zip")
        client().get("https://raw.githubusercontent.com/x/y/pack.zip")
    }

    @Test
    fun `allows loopback for the local demo server`() = runTest {
        client().get("http://127.0.0.1:8080/latest.json")
        client().get("http://10.0.2.2:8080/latest.json")
    }

    @Test
    fun `permits the configured base-url host via extraHosts`() = runTest {
        // Must not throw when the BuildConfig host is supplied dynamically.
        client(extraHosts = setOf("packs.example.dev")).get("https://packs.example.dev/latest.json")
    }

    @Test
    fun `rejects a non-allowlisted host`() = runTest {
        val ex = runCatching { client().get("https://evil.example.com/steal") }.exceptionOrNull()
        assertThat(ex).isNotNull()
        val rejected = generateSequence(ex) { it.cause }
            .any { it is IllegalStateException && it.message?.contains("rejected") == true }
        assertThat(rejected).isTrue()
    }

    @Test
    fun `extraHosts does not open the gate to other hosts`() = runTest {
        val ex = runCatching {
            client(extraHosts = setOf("packs.example.dev")).get("https://other.example.org/x")
        }.exceptionOrNull()
        assertThat(ex).isNotNull()
        val rejected = generateSequence(ex) { it.cause }
            .any { it is IllegalStateException && it.message?.contains("rejected") == true }
        assertThat(rejected).isTrue()
    }
}
