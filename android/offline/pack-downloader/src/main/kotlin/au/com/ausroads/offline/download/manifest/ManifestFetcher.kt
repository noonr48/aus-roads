package au.com.ausroads.offline.download.manifest

import au.com.ausroads.offline.pack.PackManifest
import au.com.ausroads.offline.download.state.ManifestCacheEntry
import au.com.ausroads.offline.download.state.ManifestFetchResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Lenient JSON for decoding the manifest body directly. The manifest is decoded
 * from text (not via ContentNegotiation) so the parse does not depend on the
 * server's Content-Type — GitHub Release assets are served as
 * `application/octet-stream`, which ContentNegotiation refuses to JSON-decode.
 */
private val MANIFEST_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

class ManifestFetcher(
    private val client: HttpClient,
    private val manifestCache: ManifestCache,
    /**
     * Base URL for the pack CDN, injected from the app's BuildConfig
     * (MAP_PACK_BASE_URL). The manifest lives at "$baseUrl/latest.json". Blank
     * when this build cannot download (e.g. the offline flavor) — fetch then
     * short-circuits to a clear DOWNLOADS_UNAVAILABLE failure.
     */
    private val baseUrl: String,
) {
    private val manifestUrl: String get() = "$baseUrl/latest.json"

    suspend fun fetch(): ManifestFetchResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
            return@withContext ManifestFetchResult.Failed(
                ManifestFetchResult.FailureReason.DOWNLOADS_UNAVAILABLE
            )
        }
        try {
            val cached = manifestCache.read()

            val response: HttpResponse = client.get(manifestUrl) {
                cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                cached?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
            }

            when (response.status) {
                HttpStatusCode.NotModified -> {
                    // 304: the cached manifest is still current. Surface it decoded so
                    // the caller can compare its version to the installed pack and
                    // re-download if they differ — "unchanged" is not "installed".
                    val cachedJson = cached?.manifestJson
                    if (cachedJson.isNullOrBlank()) {
                        ManifestFetchResult.Failed(ManifestFetchResult.FailureReason.UNREACHABLE)
                    } else {
                        val cachedManifest =
                            MANIFEST_JSON.decodeFromString(PackManifest.serializer(), cachedJson)
                        ManifestFetchResult.Unchanged(cachedManifest, cachedJson)
                    }
                }

                HttpStatusCode.OK -> {
                    // Decode from text, NOT response.body<PackManifest>(): GitHub serves
                    // release assets as application/octet-stream, which Ktor's
                    // ContentNegotiation won't auto-deserialize. The cached rawJson is the
                    // original manifest text the worker verifies against.
                    val rawJson = response.bodyAsText()
                    val manifest = MANIFEST_JSON.decodeFromString(PackManifest.serializer(), rawJson)
                    val etag = response.headers[HttpHeaders.ETag]
                    val lastModified = response.headers[HttpHeaders.LastModified]

                    manifestCache.write(
                        ManifestCacheEntry(
                            etag = etag,
                            lastModified = lastModified,
                            manifestJson = rawJson,
                        )
                    )

                    ManifestFetchResult.Fresh(manifest, rawJson)
                }

                HttpStatusCode.NotFound -> ManifestFetchResult.Failed(
                    ManifestFetchResult.FailureReason.NOT_FOUND
                )

                else -> ManifestFetchResult.Failed(
                    ManifestFetchResult.FailureReason.UNREACHABLE
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "ManifestFetcher",
                "manifest fetch failed: ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
            ManifestFetchResult.Failed(ManifestFetchResult.FailureReason.UNREACHABLE)
        }
    }
}
