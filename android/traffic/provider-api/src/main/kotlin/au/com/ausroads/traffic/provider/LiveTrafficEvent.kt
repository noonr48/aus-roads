/*
 * The LiveTrafficProvider interface is the architectural pivot for the country-take-over
 * plan. Every new state (AU-NSW, AU-VIC, AU-national, global) ships as a new module that
 * implements this interface. The interface is locked in v0.2; changing it after v0.2
 * ships breaks every existing adapter.
 */
package au.com.ausroads.traffic.provider

import au.com.ausroads.core.model.Region
import au.com.ausroads.core.model.RoutingEffect
import kotlinx.datetime.Instant

/**
 * One traffic event from any provider, normalised into a common shape.
 *
 * `id` is the provider's stable id; the consumer will combine it with `source` to form
 * the database primary key (`"$source:$id"`).
 */
data class LiveTrafficEvent(
    val id: String,
    val source: String,
    val sourceType: SourceType,
    val region: Region,
    val type: EventType,
    val severity: Severity,
    val description: String,
    val geometry: TrafficGeometry,
    val startTime: Instant?,
    val endTime: Instant?,
    val attributes: Map<String, String>,
    val attribution: String,
    val confidence: Double = 1.0,
    val routingEffect: RoutingEffect = RoutingEffect.None,
) {
    /** Canonical primary key for storage and dedup. */
    val primaryKey: String get() = "$source:$id"
}

/** Where this event came from. */
enum class SourceType {
    OFFICIAL,
    COMMUNITY,
    DERIVED,
}

/** Internal event category. Providers map their native `REC_TYPE` etc. to these. */
enum class EventType {
    ROADWORKS,
    INCIDENT,
    CLOSURE,
    DETOUR,
    EVENT,
    OUTBACK_WARNING,
    UNKNOWN,
}

/** Severity. Drives the marker color in the map overlay. */
enum class Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/** Geometry of a traffic event. Closures/detours are LineString, the rest are Point. */
sealed class TrafficGeometry {
    data class Point(val longitude: Double, val latitude: Double) : TrafficGeometry()
    data class LineString(val coordinates: List<Pair<Double, Double>>) : TrafficGeometry()
}
