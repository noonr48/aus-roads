package au.com.ausroads.offline.download.network

import io.ktor.client.plugins.api.createClientPlugin

/**
 * Hosts always permitted by the endpoint gate (the production CDN + the GitHub
 * mirrors used for sideloaded manifests).
 */
private val BASE_ALLOWED_HOSTS = setOf(
    "github.com",
    "raw.githubusercontent.com",
    // GitHub Release asset downloads (anonymous pack hosting) 302-redirect to these.
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
)

/** Loopback hosts for the local demo server (debug builds point MAP_PACK_BASE_URL here). */
private val LOOPBACK_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

/**
 * Builds the Ktor plugin that rejects requests to hosts not in the allowlist.
 * This is the privacy audit's endpoint gate — even if someone constructs a
 * client, requests to non-allowlisted hosts are rejected at runtime.
 *
 * [extraHosts] lets the configured base URL's host (from BuildConfig) be
 * permitted dynamically; loopback hosts are always included so the local demo
 * server works in debug.
 */
fun hostAllowlistPlugin(extraHosts: Set<String> = emptySet()) =
    createClientPlugin("HostAllowlist") {
        val allowedHosts = BASE_ALLOWED_HOSTS + LOOPBACK_HOSTS + extraHosts

        onRequest { requestBuilder, _ ->
            val host = requestBuilder.url.host
            if (host !in allowedHosts) {
                throw IllegalStateException(
                    "HostAllowlist: request to '$host' rejected. " +
                        "Only $allowedHosts are allowed."
                )
            }
        }
    }
