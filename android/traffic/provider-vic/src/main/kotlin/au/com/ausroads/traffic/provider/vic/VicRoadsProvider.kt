package au.com.ausroads.traffic.provider.vic

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
import io.ktor.client.request.parameter
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * VicRoads REST API provider.
 *
 * Docs: https://opendata.transport.vic.gov.au/
 * Auth: `KeyID: <KEY>` header (free registration, 20 req/min rate limit)
 * Planned disruptions: /opendata/roads/disruptions/planned/v1/?format=GeoJson
 * Unplanned disruptions: /opendata/roads/disruptions/unplanned/v2/?page=1&limit=0
 * Response: GeoJSON FeatureCollection with `meta`/`links` envelope
 */
@Singleton
class VicRoadsProvider @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : LiveTrafficProvider {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    override val regionCode: String = "AU-VIC"
    override val displayName: String = "VicRoads"
    override val supportedBbox: Bbox = Bbox(
        west = 141.0,
        south = -39.2,
        east = 150.0,
        north = -33.5,
    )

    private val baseUrl = "https://api.opendata.transport.vic.gov.au/opendata/roads/disruptions"

    // API key — read from user settings (Settings screen)
    // Register at https://opendata.transport.vic.gov.au for a free key
    // suspend (not a runBlocking getter): callers are already on Dispatchers.IO.
    private suspend fun resolveApiKey(): String =
        try {
            settingsRepository.settings
                .first()
                .vicTrafficApiKey
                .ifEmpty { System.getenv("VIC_TRAFFIC_API_KEY") ?: "" }
        } catch (_: Exception) {
            System.getenv("VIC_TRAFFIC_API_KEY") ?: ""
        }

    override suspend fun fetchEvents(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = withContext(Dispatchers.IO) {
        // Planned disruptions (roadworks, events)
        fetchPlanned(bbox = bbox, ifNoneMatch = ifNoneMatch)
    }

    override suspend fun fetchClosures(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = withContext(Dispatchers.IO) {
        // Unplanned disruptions (crashes, closures, hazards)
        fetchUnplanned(bbox = bbox, ifNoneMatch = ifNoneMatch)
    }

    private suspend fun fetchPlanned(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult {
        val url = "$baseUrl/planned/v1/"
        val apiKey = resolveApiKey()

        val response: HttpResponse = client.get(url) {
            parameter("format", "GeoJson")
            if (apiKey.isNotEmpty()) {
                header("KeyID", apiKey)
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

        return parseGeoJsonResponse(response, EventType.ROADWORKS, bbox)
    }

    private suspend fun fetchUnplanned(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult {
        val url = "$baseUrl/unplanned/v2/"
        val apiKey = resolveApiKey()

        val response: HttpResponse = client.get(url) {
            parameter("page", "1")
            parameter("limit", "0") // All results
            if (apiKey.isNotEmpty()) {
                header("KeyID", apiKey)
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

        return parseGeoJsonResponse(response, EventType.INCIDENT, bbox)
    }

    private suspend fun parseGeoJsonResponse(
        response: HttpResponse,
        defaultType: EventType,
        bbox: Bbox?,
    ): FetchResult {
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
        val id = props["id"]?.jsonPrimitive?.content
            ?: props["OBJECTID"]?.jsonPrimitive?.int?.toString()
            ?: return null

        // Parse geometry — VIC uses Point or GeometryCollection
        val geometry = parseGeometry(feature["geometry"]?.jsonObject) ?: return null

        // Bbox filter
        if (bbox != null && !isInBbox(geometry, bbox)) return null

        val eventType = props["eventType"]?.jsonPrimitive?.content ?: ""
        val type = mapEventType(eventType, defaultType)
        val impactType = props["impact"]?.jsonObject?.get("impactType")?.jsonPrimitive?.content ?: ""
        val severity = mapSeverityFromImpact(impactType)
        val description = props["description"]?.jsonPrimitive?.content
            ?: props["closedRoadName"]?.jsonPrimitive?.content
            ?: type.name

        // Road name
        val roadName = props["closedRoadName"]?.jsonPrimitive?.content ?: ""
        val fullDescription = if (roadName.isNotEmpty() && !description.contains(roadName)) {
            "$description — $roadName"
        } else {
            description
        }

        // Parse timestamps (ISO format)
        val startTime = props["created"]?.jsonPrimitive?.content?.let { parseTimestamp(it) }
        val endTime = props["lastClosed"]?.jsonPrimitive?.content?.let { parseTimestamp(it) }

        return LiveTrafficEvent(
            id = id,
            source = "traffic-vic",
            sourceType = SourceType.OFFICIAL,
            region = Region(country = "AU", state = "vic"),
            type = type,
            severity = severity,
            description = fullDescription,
            geometry = geometry,
            startTime = startTime,
            endTime = endTime,
            attributes = mapOf(
                "eventType" to eventType,
                "impactType" to impactType,
                "status" to (props["status"]?.jsonPrimitive?.content ?: ""),
            ),
            attribution = "© VicRoads, State Government of Victoria",
            confidence = 1.0,
            routingEffect = mapRoutingEffect(type),
        )
    }

    private fun parseGeometry(geo: JsonObject?): TrafficGeometry? {
        if (geo == null) return null
        val type = geo["type"]?.jsonPrimitive?.content ?: return null

        return when (type) {
            "Point" -> {
                val coords = geo["coordinates"]?.jsonArray ?: return null
                if (coords.size < 2) return null
                TrafficGeometry.Point(
                    longitude = coords[0].jsonPrimitive.double,
                    latitude = coords[1].jsonPrimitive.double,
                )
            }
            "LineString" -> {
                val coords = geo["coordinates"]?.jsonArray ?: return null
                val points = coords.mapNotNull { point ->
                    val arr = point.jsonArray
                    if (arr.size >= 2) Pair(arr[0].jsonPrimitive.double, arr[1].jsonPrimitive.double)
                    else null
                }
                if (points.isNotEmpty()) TrafficGeometry.LineString(points) else null
            }
            "GeometryCollection" -> {
                // VIC planned disruptions use GeometryCollection — extract first LineString
                val geometries = geo["geometries"]?.jsonArray ?: return null
                for (g in geometries) {
                    val result = parseGeometry(g.jsonObject)
                    if (result != null) return result
                }
                null
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

    private fun mapEventType(eventType: String, default: EventType): EventType = when (eventType) {
        "Roadworks" -> EventType.ROADWORKS
        "Incident" -> EventType.INCIDENT
        "Hazard" -> EventType.INCIDENT
        "Crash" -> EventType.INCIDENT
        "Flooding" -> EventType.INCIDENT
        "Fire" -> EventType.INCIDENT
        else -> default
    }

    private fun mapSeverityFromImpact(impactType: String): Severity = when (impactType) {
        "Road closed" -> Severity.CRITICAL
        "Lanes closed" -> Severity.HIGH
        "Traffic delays" -> Severity.MEDIUM
        "Changed conditions" -> Severity.LOW
        "Detour" -> Severity.MEDIUM
        else -> Severity.MEDIUM
    }

    private fun mapRoutingEffect(type: EventType): RoutingEffect = when (type) {
        EventType.CLOSURE -> RoutingEffect.Block
        EventType.DETOUR -> RoutingEffect.Penalty(delayMinutes = 10)
        EventType.ROADWORKS -> RoutingEffect.Penalty(delayMinutes = 5)
        else -> RoutingEffect.None
    }

    private fun parseTimestamp(value: String): Instant? {
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            null
        }
    }
}
