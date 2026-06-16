package au.com.ausroads.traffic.provider.sa

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.core.model.RoutingEffect
import au.com.ausroads.traffic.provider.ArcGisFeature
import au.com.ausroads.traffic.provider.ArcGisFeatureCollection
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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

@Singleton
class TrafficSaProvider @Inject constructor() : LiveTrafficProvider {

    // Own HttpClient — no HostAllowlist (needs to reach trafficdata.geohub.sa.gov.au)
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    override val regionCode: String = "AU-SA"
    override val displayName: String = "Traffic SA"
    override val supportedBbox: Bbox = Bbox(
        west = 129.0,
        south = -38.0,
        east = 141.0,
        north = -26.0,
    )

    // Production MapServer — requires Referer header from maps.sa.gov.au
    private val baseUrl = "https://trafficdata.geohub.sa.gov.au/MapServer"
    private val referer = "https://maps.sa.gov.au/TrafficSATime/"

    override suspend fun fetchEvents(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = withContext(Dispatchers.IO) {
        fetchLayer(layerId = 0, bbox = bbox, ifNoneMatch = ifNoneMatch, eventType = EventType.ROADWORKS)
    }

    override suspend fun fetchClosures(
        bbox: Bbox?,
        ifNoneMatch: String?,
    ): FetchResult = withContext(Dispatchers.IO) {
        // Layer 4: Road Alerts (closures, detours) as polylines
        fetchLayer(layerId = 4, bbox = bbox, ifNoneMatch = ifNoneMatch, eventType = EventType.CLOSURE)
    }

    private suspend fun fetchLayer(
        layerId: Int,
        bbox: Bbox?,
        ifNoneMatch: String?,
        eventType: EventType,
    ): FetchResult {
        val queryUrl = "$baseUrl/$layerId/query"

        val response: HttpResponse = client.get(queryUrl) {
            parameter("where", "1=1")
            parameter("outFields", "*")
            parameter("f", "geojson")
            parameter("returnGeometry", "true")
            header("Referer", referer)
            if (bbox != null) {
                parameter("geometry", "${bbox.west},${bbox.south},${bbox.east},${bbox.north}")
                parameter("geometryType", "esriGeometryEnvelope")
                parameter("inSR", "4326")
                parameter("spatialRel", "esriSpatialRelIntersects")
            }
            ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
        }

        if (response.status == HttpStatusCode.NotModified) {
            val etag = response.headers[HttpHeaders.ETag]
            return FetchResult(
                events = emptyList(),
                serverTimestamp = Clock.System.now(),
                cacheMaxAge = 5.minutes,
                etag = etag,
                lastModified = response.headers[HttpHeaders.LastModified],
            )
        }

        val geoJson: ArcGisFeatureCollection = response.body()
        val etag = response.headers[HttpHeaders.ETag]
        val lastModified = response.headers[HttpHeaders.LastModified]

        val events = geoJson.features.mapNotNull { feature ->
            mapFeatureToEvent(feature, eventType)
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
        feature: ArcGisFeature,
        defaultType: EventType,
    ): LiveTrafficEvent? {
        val props = feature.properties
        val id = (props["OBJECTID"] ?: props["LOCATION_ID"])?.toString() ?: return null

        val type = mapEventType(
            props["REC_TYPE"]?.toString() ?: props["PLOT_TYPE"]?.toString(),
            defaultType,
        )
        val severity = mapSeverity(props["SEVERITY"]?.toString())
        val description = props["DESCRIPTION"]?.toString()
            ?: props["DETAILS"]?.toString()
            ?: props["PLOT_DETAILS"]?.toString()
            ?: props["PLOT_ALT_TEXT"]?.toString()
            ?: type.name

        val geometry = feature.geometry?.toTrafficGeometry()
            ?: return null

        val startTime = props["START_DATE"]?.toString()?.let { parseTimestamp(it) }
        val endTime = props["END_DATE"]?.toString()?.let { parseTimestamp(it) }

        return LiveTrafficEvent(
            id = id,
            source = "traffic-sa",
            sourceType = SourceType.OFFICIAL,
            region = Region(country = "AU", state = "sa"),
            type = type,
            severity = severity,
            description = description,
            geometry = geometry,
            startTime = startTime,
            endTime = endTime,
            attributes = props.mapValues { it.value?.toString() ?: "" },
            attribution = "© Government of South Australia, Department for Infrastructure and Transport",
            confidence = 1.0,
            routingEffect = mapRoutingEffect(type),
        )
    }

    private fun mapEventType(recType: String?, default: EventType): EventType = when (recType?.uppercase()) {
        "ROADWORKS", "24HR ROADWORKS", "CONSTRUCTION" -> EventType.ROADWORKS
        "INCIDENT", "ACCIDENT", "CRASH", "COLLISION" -> EventType.INCIDENT
        "ROAD CLOSURE", "CLOSURE", "CLOSED" -> EventType.CLOSURE
        "RD_CLOSURE", "RD_CLOSURE_1", "RD_CLOSURE_2", "RD_CLOSURE_3", "RD_CLOSURE_4" -> EventType.CLOSURE
        "DETOUR", "RD_DETOUR" -> EventType.DETOUR
        "EVENT", "SPECIAL EVENT" -> EventType.EVENT
        "FIRE", "FLOODING", "EMERGENCY WATERWORKS", "SIGNAL FAULT" -> EventType.INCIDENT
        else -> default
    }

    private fun mapSeverity(severity: String?): Severity = when (severity?.uppercase()) {
        "LOW", "MINOR" -> Severity.LOW
        "MEDIUM", "MODERATE" -> Severity.MEDIUM
        "HIGH", "MAJOR" -> Severity.HIGH
        "CRITICAL", "EMERGENCY" -> Severity.CRITICAL
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
            val millis = value.toLongOrNull()
            if (millis != null) {
                Instant.fromEpochMilliseconds(millis)
            } else {
                Instant.parse(value)
            }
        } catch (_: Exception) {
            null
        }
    }
}
