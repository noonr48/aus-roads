package au.com.ausroads.traffic.provider.nsw

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.core.model.RoutingEffect
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.traffic.provider.EventType
import au.com.ausroads.traffic.provider.FetchResult
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.traffic.provider.LiveTrafficProvider
import au.com.ausroads.traffic.provider.Severity
import au.com.ausroads.traffic.provider.SourceType
import au.com.ausroads.traffic.provider.TrafficGeometry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * NSW Live Traffic REST API provider.
 *
 * Docs: https://opendata.transport.nsw.gov.au/
 * Auth: `Authorization: apikey <KEY>` header (free registration)
 * Response: Raw GeoJSON FeatureCollection (no envelope)
 * Geometry: POINT (uppercase — non-standard, handled in parsing)
 * Time: Epoch milliseconds
 */
@Singleton
class NswLiveTrafficProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : LiveTrafficProvider {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    override val regionCode: String = "AU-NSW"
    override val displayName: String = "NSW Live Traffic"
    override val supportedBbox: Bbox = Bbox(
        west = 140.0,
        south = -37.5,
        east = 153.5,
        north = -28.0,
    )

    // NSW Live Traffic REST API — requires API key from opendata.transport.nsw.gov.au
    private val baseUrl = "https://api.transport.nsw.gov.au/v1/live/hazards"

    // API key — read from user settings (Settings screen).
    // Register at https://opendata.transport.nsw.gov.au for a free key.
    // suspend (not a runBlocking getter): callers are already on Dispatchers.IO.
    private suspend fun resolveApiKey(): String =
        try {
            settingsRepository.settings
                .first()
                .nswTrafficApiKey
                .ifEmpty { System.getenv("NSW_TRAFFIC_API_KEY") ?: "" }
        } catch (_: Exception) {
            System.getenv("NSW_TRAFFIC_API_KEY") ?: ""
        }

    override suspend fun fetchEvents(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = withContext(Dispatchers.IO) {
        fetchEndpoint(
            endpoint = "incident/open",
            bbox = bbox,
            ifNoneMatch = ifNoneMatch,
            defaultType = EventType.INCIDENT,
        )
    }

    override suspend fun fetchClosures(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = withContext(Dispatchers.IO) {
        fetchEndpoint(
            endpoint = "roadwork/open",
            bbox = bbox,
            ifNoneMatch = ifNoneMatch,
            defaultType = EventType.ROADWORKS,
        )
    }

    private suspend fun fetchEndpoint(
        endpoint: String,
        bbox: Bbox?,
        ifNoneMatch: String?,
        defaultType: EventType,
    ): FetchResult {
        val url = "$baseUrl/$endpoint"
        val apiKey = resolveApiKey()

        val response: HttpResponse = client.get(url) {
            if (apiKey.isNotEmpty()) {
                header("Authorization", "apikey $apiKey")
            }
            ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
        }

        if (response.status == HttpStatusCode.NotModified) {
            return FetchResult(
                events = emptyList(),
                serverTimestamp = Clock.System.now(),
                cacheMaxAge = 5.minutes,
                etag = response.headers[HttpHeaders.ETag],
                lastModified = response.headers[HttpHeaders.LastModified],
            )
        }

        // A non-success status (401 missing/bad key, 5xx, …) is not a JSON
        // FeatureCollection — degrade to an empty result instead of throwing.
        if (!response.status.isSuccess()) {
            return FetchResult(
                events = emptyList(),
                serverTimestamp = Clock.System.now(),
                cacheMaxAge = 5.minutes,
                etag = response.headers[HttpHeaders.ETag],
                lastModified = response.headers[HttpHeaders.LastModified],
            )
        }

        val json = response.body<JsonObject>()
        val features = json["features"]?.jsonArray ?: JsonArray(emptyList())
        val etag = response.headers[HttpHeaders.ETag]
        val lastModified = response.headers[HttpHeaders.LastModified]

        val events = features.mapNotNull { element ->
            val feature = element.jsonObject
            mapFeatureToEvent(feature, defaultType, bbox)
        }

        return FetchResult(
            events = events,
            serverTimestamp = Clock.System.now(),
            cacheMaxAge = 5.minutes,
            etag = etag,
            lastModified = lastModified,
        )
    }

    private fun mapFeatureToEvent(
        feature: JsonObject,
        defaultType: EventType,
        bbox: Bbox?,
    ): LiveTrafficEvent? {
        val props = feature["properties"]?.jsonObject ?: return null
        val id = props["id"]?.jsonPrimitive?.int?.toString()
            ?: props["OBJECTID"]?.jsonPrimitive?.int?.toString()
            ?: return null

        // Parse geometry — NSW uses uppercase "POINT" (non-standard)
        val geometry = parseGeometry(feature["geometry"]?.jsonObject) ?: return null

        // Bbox filter (NSW API doesn't support bbox query params)
        if (bbox != null && !isInBbox(geometry, bbox)) return null

        val mainCategory = props["mainCategory"]?.jsonPrimitive?.content ?: ""
        val type = mapEventType(mainCategory, defaultType)
        val isMajor = props["isMajor"]?.jsonPrimitive?.boolean ?: false
        val severity = if (isMajor) Severity.HIGH else mapSeverityFromAdvice(props["adviceA"]?.jsonPrimitive?.content)
        val headline = props["headline"]?.jsonPrimitive?.content
            ?: props["displayName"]?.jsonPrimitive?.content
            ?: type.name

        // Road name from roads array
        val roadName = props["roads"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("mainStreet")?.jsonPrimitive?.content ?: ""

        val description = if (roadName.isNotEmpty()) "$headline — $roadName" else headline

        // Time from created field (epoch ms)
        val startTime = props["created"]?.jsonPrimitive?.long?.let { Instant.fromEpochMilliseconds(it) }

        return LiveTrafficEvent(
            id = id,
            source = "traffic-nsw",
            sourceType = SourceType.OFFICIAL,
            region = Region(country = "AU", state = "nsw"),
            type = type,
            severity = severity,
            description = description,
            geometry = geometry,
            startTime = startTime,
            endTime = null,
            attributes = mapOf(
                "mainCategory" to mainCategory,
                "isMajor" to isMajor.toString(),
                "adviceA" to (props["adviceA"]?.jsonPrimitive?.content ?: ""),
            ),
            attribution = "© Transport for NSW",
            confidence = 1.0,
            routingEffect = mapRoutingEffect(type),
        )
    }

    private fun parseGeometry(geo: JsonObject?): TrafficGeometry? {
        if (geo == null) return null
        val type = geo["type"]?.jsonPrimitive?.content?.uppercase() ?: return null
        val coords = geo["coordinates"]?.jsonArray ?: return null

        return when (type) {
            "POINT" -> {
                if (coords.size < 2) return null
                TrafficGeometry.Point(
                    longitude = coords[0].jsonPrimitive.double,
                    latitude = coords[1].jsonPrimitive.double,
                )
            }
            "LINESTRING" -> {
                val points = coords.mapNotNull { point ->
                    val arr = point.jsonArray
                    if (arr.size >= 2) Pair(arr[0].jsonPrimitive.double, arr[1].jsonPrimitive.double)
                    else null
                }
                if (points.isNotEmpty()) TrafficGeometry.LineString(points) else null
            }
            else -> null
        }
    }

    private fun isInBbox(geometry: TrafficGeometry, bbox: Bbox): Boolean = when (geometry) {
        is TrafficGeometry.Point ->
            geometry.longitude in bbox.west..bbox.east && geometry.latitude in bbox.south..bbox.north
        is TrafficGeometry.LineString ->
            geometry.coordinates.any { (lon, lat) -> lon in bbox.west..bbox.east && lat in bbox.south..bbox.north }
    }

    private fun mapEventType(category: String, default: EventType): EventType = when (category) {
        "Roadwork" -> EventType.ROADWORKS
        "Crash" -> EventType.INCIDENT
        "Flooding" -> EventType.INCIDENT
        "Fire" -> EventType.INCIDENT
        "Hazard" -> EventType.INCIDENT
        "Major Event" -> EventType.EVENT
        else -> default
    }

    private fun mapSeverityFromAdvice(advice: String?): Severity = when (advice?.lowercase()) {
        "exercise caution", "check conditions" -> Severity.LOW
        "allow extra travel time", "avoid the area" -> Severity.MEDIUM
        "road closed", "use alternate route" -> Severity.HIGH
        else -> Severity.MEDIUM
    }

    private fun mapRoutingEffect(type: EventType): RoutingEffect = when (type) {
        EventType.CLOSURE -> RoutingEffect.Block
        EventType.DETOUR -> RoutingEffect.Penalty(delayMinutes = 10)
        EventType.ROADWORKS -> RoutingEffect.Penalty(delayMinutes = 5)
        else -> RoutingEffect.None
    }
}
