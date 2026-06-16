package au.com.ausroads.traffic.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

@kotlinx.serialization.Serializable
data class ArcGisFeatureCollection(
    val type: String = "FeatureCollection",
    val features: List<ArcGisFeature> = emptyList(),
)

@kotlinx.serialization.Serializable
data class ArcGisFeature(
    val type: String = "Feature",
    val properties: Map<String, JsonElement?> = emptyMap(),
    val geometry: ArcGisGeometry? = null,
)

@kotlinx.serialization.Serializable
data class ArcGisGeometry(
    val type: String,
    val coordinates: JsonElement? = null,
) {
    fun toTrafficGeometry(): TrafficGeometry? {
        val coords = coordinates ?: return null
        return when (type) {
            "Point" -> parsePoint(coords)
            "LineString" -> parseLineString(coords)
            "MultiLineString" -> parseMultiLineString(coords)
            else -> null
        }
    }

    private fun parsePoint(coords: JsonElement): TrafficGeometry.Point? {
        val arr = (coords as? JsonArray) ?: return null
        if (arr.size < 2) return null
        val lon = arr[0].jsonPrimitive.double
        val lat = arr[1].jsonPrimitive.double
        return TrafficGeometry.Point(longitude = lon, latitude = lat)
    }

    private fun parseLineString(coords: JsonElement): TrafficGeometry.LineString? {
        val arr = (coords as? JsonArray) ?: return null
        val points = arr.mapNotNull { point ->
            val p = (point as? JsonArray) ?: return@mapNotNull null
            if (p.size >= 2) {
                Pair(p[0].jsonPrimitive.double, p[1].jsonPrimitive.double)
            } else null
        }
        return if (points.isNotEmpty()) TrafficGeometry.LineString(points) else null
    }

    private fun parseMultiLineString(coords: JsonElement): TrafficGeometry.LineString? {
        val arr = (coords as? JsonArray) ?: return null
        val firstLine = (arr.firstOrNull() as? JsonArray) ?: return null
        val points = firstLine.mapNotNull { point ->
            val p = (point as? JsonArray) ?: return@mapNotNull null
            if (p.size >= 2) {
                Pair(p[0].jsonPrimitive.double, p[1].jsonPrimitive.double)
            } else null
        }
        return if (points.isNotEmpty()) TrafficGeometry.LineString(points) else null
    }
}
