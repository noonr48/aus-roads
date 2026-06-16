package au.com.ausroads.offline.download.manifest

import au.com.ausroads.offline.pack.PackManifest
import au.com.ausroads.offline.download.state.ManifestCacheEntry
import au.com.ausroads.offline.download.state.ManifestFetchResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                HttpStatusCode.NotModified -> ManifestFetchResult.Unchanged

                HttpStatusCode.OK -> {
                    val manifest: PackManifest = response.body()
                    val etag = response.headers[HttpHeaders.ETag]
                    val lastModified = response.headers[HttpHeaders.LastModified]
                    val rawJson = kotlinx.serialization.json.Json.encodeToString(
                        PackManifest.serializer(), manifest
                    )

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
        } catch (_: Exception) {
            ManifestFetchResult.Failed(ManifestFetchResult.FailureReason.UNREACHABLE)
        }
    }
}
