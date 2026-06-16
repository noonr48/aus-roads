package au.com.ausroads.traffic.provider.sa.outback

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.core.model.RoutingEffect
import au.com.ausroads.traffic.provider.EventType
import au.com.ausroads.traffic.provider.FetchResult
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.traffic.provider.LiveTrafficProvider
import au.com.ausroads.traffic.provider.Severity
import au.com.ausroads.traffic.provider.SourceType
import au.com.ausroads.traffic.provider.ArcGisFeature
import au.com.ausroads.traffic.provider.ArcGisFeatureCollection
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * DIT outback road conditions provider.
 * Fetches from maps.sa.gov.au ArcGIS MapServer — no auth, no Referer required.
 * Polls at 15-minute cadence (outback data changes infrequently).
 */
@Singleton
class SaOutbackProvider @Inject constructor() : LiveTrafficProvider {

    override val regionCode: String = "AU-SA-OUTBACK"
    override val displayName: String = "DIT Outback Roads"
    override val supportedBbox: Bbox = Bbox(
        west = 129.0,
        south = -38.0,
        east = 141.0,
        north = -26.0,
    )

    private val baseUrl = "https://maps.sa.gov.au/arcgis/rest/services/DPTIExtTransport/FNRR2/MapServer"

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    override suspend fun fetchEvents(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = withContext(Dispatchers.IO) {
        // Outback warnings are road conditions, not point events.
        // Return empty for fetchEvents — use fetchClosures instead.
        FetchResult.Empty
    }

    override suspend fun fetchClosures(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = withContext(Dispatchers.IO) {
        val queryUrl = "$baseUrl/0/query"

        val response: HttpResponse = client.get(queryUrl) {
            parameter("where", "CODE<>1")
            parameter("outFields", "*")
            parameter("f", "geojson")
            parameter("returnGeometry", "true")
            if (bbox != null) {
                parameter("geometry", "${bbox.west},${bbox.south},${bbox.east},${bbox.north}")
                parameter("geometryType", "esriGeometryEnvelope")
                parameter("inSR", "4326")
                parameter("spatialRel", "esriSpatialRelIntersects")
            }
            ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
        }

        if (response.status == HttpStatusCode.NotModified) {
            return@withContext FetchResult(
                events = emptyList(),
                serverTimestamp = Clock.System.now(),
                cacheMaxAge = 15.minutes,
                etag = response.headers[HttpHeaders.ETag],
                lastModified = null,
            )
        }

        val geoJson: ArcGisFeatureCollection = response.body()
        val etag = response.headers[HttpHeaders.ETag]

        val events = geoJson.features.mapNotNull { feature ->
            mapFeatureToEvent(feature)
        }

        FetchResult(
            events = events,
            serverTimestamp = Clock.System.now(),
            cacheMaxAge = 15.minutes,
            etag = etag,
            lastModified = null,
        )
    }

    private fun mapFeatureToEvent(feature: ArcGisFeature): LiveTrafficEvent? {
        val props = feature.properties
        val id = props["OBJECTID"]?.toString() ?: return null

        val code = (props["CODE"]?.toString()?.toIntOrNull()) ?: 2
        val severity = mapCodeToSeverity(code)
        val roadSection = props["ROAD_SECTION"]?.toString() ?: "Unknown road"
        val comments = props["COMMENTS"]?.toString() ?: ""

        val geometry = feature.geometry?.toTrafficGeometry()
            ?: return null

        val eventType = if (code == 5) EventType.CLOSURE else EventType.OUTBACK_WARNING

        return LiveTrafficEvent(
            id = id,
            source = "dit-outback",
            sourceType = SourceType.OFFICIAL,
            region = Region(country = "AU", state = "sa"),
            type = eventType,
            severity = severity,
            description = buildString {
                append(roadSection)
                if (comments.isNotBlank()) {
                    append(" — ")
                    append(comments.take(200))
                }
            },
            geometry = geometry,
            startTime = null,
            endTime = null,
            attributes = props.mapValues { it.value?.toString() ?: "" },
            attribution = "© Government of South Australia, Department for Infrastructure and Transport",
            confidence = 1.0,
            routingEffect = when (code) {
                5 -> RoutingEffect.Block
                3, 4 -> RoutingEffect.Penalty(delayMinutes = 15)
                else -> RoutingEffect.DisplayOnly()
            },
        )
    }

    private fun mapCodeToSeverity(code: Int): Severity = when (code) {
        5 -> Severity.CRITICAL  // Closed
        3 -> Severity.HIGH      // 4WD/HV
        4 -> Severity.MEDIUM    // 4WD
        2 -> Severity.LOW       // Open With Warnings
        else -> Severity.LOW
    }
}
