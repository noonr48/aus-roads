package au.com.ausroads.offline.download.manifest

import au.com.ausroads.offline.download.state.ManifestCacheEntry
import au.com.ausroads.offline.download.state.ManifestFetchResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class ManifestFetcherTest {

    private lateinit var manifestCache: ManifestCache

    private companion object {
        const val BASE_URL = "https://cdn.aus-roads.example"
    }

    @Before
    fun setUp() {
        manifestCache = mockk(relaxed = true)
    }

    private fun makeClient(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"schemaVersion":1,"packVersion":"test","region":{"country":"AU","state":"SA"},"bbox":{"west":138.0,"south":-35.0,"east":139.0,"north":-34.0},"generatedAt":"2026-01-01T00:00:00Z","osmSource":{"provider":"geofabrik","url":"https://example.com","osmExtractDate":"2026-01-01T00:00:00Z"},"license":"ODbL-1.0","minAppVersion":"1.0.0","minAndroidSdk":26,"components":{"tiles":{"format":"mbtiles","schema":"openmaptiles","minZoom":0,"maxZoom":14,"path":"tiles.mbtiles","sizeBytes":100,"sha256":"abc"},"routing":{"format":"none","profile":"none","path":"n/a","sizeBytes":0,"sha256":"none"},"search":{"format":"none","path":"n/a","sizeBytes":0,"sha256":"none"}},"totalSizeBytes":100,"signatures":{}}""",
        etag: String? = null,
        lastModified: String? = null,
        contentType: String = "application/json",
    ) = HttpClient(MockEngine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        engine {
            addHandler {
                respond(
                    content = body.toByteArray(),
                    status = status,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf(contentType),
                        *listOfNotNull(
                            etag?.let { HttpHeaders.ETag to listOf(it) },
                            lastModified?.let { HttpHeaders.LastModified to listOf(it) },
                        ).toTypedArray(),
                    ),
                )
            }
        }
    }

    @Test
    fun `fetch returns Fresh on 200 OK`() = runTest {
        coEvery { manifestCache.read() } returns null
        val client = makeClient()

        val fetcher = ManifestFetcher(client, manifestCache, BASE_URL)
        val result = fetcher.fetch()

        assertThat(result).isInstanceOf(ManifestFetchResult.Fresh::class.java)
    }

    @Test
    fun `fetch parses a manifest served as octet-stream (GitHub Release assets)`() = runTest {
        // GitHub serves release assets as application/octet-stream — Ktor's
        // ContentNegotiation refuses to JSON-decode that, so the fetcher must decode
        // the manifest from text. Regression guard for the production-hosting bug.
        coEvery { manifestCache.read() } returns null
        val client = makeClient(contentType = "application/octet-stream")

        val result = ManifestFetcher(client, manifestCache, BASE_URL).fetch()

        assertThat(result).isInstanceOf(ManifestFetchResult.Fresh::class.java)
    }

    @Test
    fun `fetch caches etag and lastModified from response`() = runTest {
        coEvery { manifestCache.read() } returns null
        val client = makeClient(etag = "\"abc123\"", lastModified = "Wed, 01 Jan 2026 00:00:00 GMT")

        val fetcher = ManifestFetcher(client, manifestCache, BASE_URL)
        fetcher.fetch()

        coVerify {
            manifestCache.write(match {
                it.etag == "\"abc123\"" && it.lastModified == "Wed, 01 Jan 2026 00:00:00 GMT"
            })
        }
    }

    @Test
    fun `fetch sends If-None-Match when cache has etag`() = runTest {
        coEvery { manifestCache.read() } returns ManifestCacheEntry(
            etag = "\"cached-etag\"",
            lastModified = null,
            manifestJson = "{}",
        )
        var receivedEtag: String? = null
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            engine {
                addHandler { request ->
                    receivedEtag = request.headers[HttpHeaders.IfNoneMatch]
                    respond(
                        content = ByteArray(0),
                        status = HttpStatusCode.NotModified,
                    )
                }
            }
        }

        val fetcher = ManifestFetcher(client, manifestCache, BASE_URL)
        val result = fetcher.fetch()

        assertThat(receivedEtag).isEqualTo("\"cached-etag\"")
        assertThat(result).isEqualTo(ManifestFetchResult.Unchanged)
    }

    @Test
    fun `fetch returns Unchanged on 304 Not Modified`() = runTest {
        coEvery { manifestCache.read() } returns ManifestCacheEntry(
            etag = "\"etag\"",
            lastModified = null,
            manifestJson = "{}",
        )
        val client = makeClient(status = HttpStatusCode.NotModified)

        val fetcher = ManifestFetcher(client, manifestCache, BASE_URL)
        val result = fetcher.fetch()

        assertThat(result).isEqualTo(ManifestFetchResult.Unchanged)
    }

    @Test
    fun `fetch returns Failed on 404`() = runTest {
        coEvery { manifestCache.read() } returns null
        val client = makeClient(status = HttpStatusCode.NotFound)

        val fetcher = ManifestFetcher(client, manifestCache, BASE_URL)
        val result = fetcher.fetch()

        assertThat(result).isInstanceOf(ManifestFetchResult.Failed::class.java)
        val failed = result as ManifestFetchResult.Failed
        assertThat(failed.reason).isEqualTo(ManifestFetchResult.FailureReason.NOT_FOUND)
    }

    @Test
    fun `fetch short-circuits to DOWNLOADS_UNAVAILABLE when base URL is blank`() = runTest {
        // A build that can't download (e.g. the offline flavor) injects "" — fetch
        // must fail clearly without reading the cache or making a request.
        val client = makeClient() // any non-blank client; should never be hit
        val fetcher = ManifestFetcher(client, manifestCache, "")

        val result = fetcher.fetch()

        assertThat(result).isInstanceOf(ManifestFetchResult.Failed::class.java)
        assertThat((result as ManifestFetchResult.Failed).reason)
            .isEqualTo(ManifestFetchResult.FailureReason.DOWNLOADS_UNAVAILABLE)
        coVerify(exactly = 0) { manifestCache.read() }
    }
}
