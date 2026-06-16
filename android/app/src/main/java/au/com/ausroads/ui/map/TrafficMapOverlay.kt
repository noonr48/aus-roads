package au.com.ausroads.ui.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.traffic.provider.TrafficGeometry
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val SOURCE_ID = "traffic-events"
private const val POINTS_LAYER = "traffic-points"
private const val LINES_LAYER = "traffic-lines"
private const val LABELS_LAYER = "traffic-labels"

// Must be a font stack bundled under app/src/main/assets/glyphs/. Omitting it
// defaults to a stack including an unbundled font, whose missing glyphs abort the
// whole render frame (taking the traffic markers and base map down with it).
private val TRAFFIC_LABEL_FONT = arrayOf("Open Sans Regular")

// Severity colors
private const val COLOR_LOW = "#4CAF50"
private const val COLOR_MEDIUM = "#FF9800"
private const val COLOR_HIGH = "#F44336"
private const val COLOR_CRITICAL = "#9C27B0"
private const val COLOR_DEFAULT = "#FF9800"

/**
 * MapLibre expression that maps severity property to a color.
 */
private fun severityColorExpr(): Expression = Expression.match(
    Expression.get("severity"),
    Expression.color(Color.parseColor(COLOR_LOW)),
    Expression.literal("LOW"),
    Expression.color(Color.parseColor(COLOR_MEDIUM)),
    Expression.literal("MEDIUM"),
    Expression.color(Color.parseColor(COLOR_HIGH)),
    Expression.literal("HIGH"),
    Expression.color(Color.parseColor(COLOR_CRITICAL)),
    Expression.literal("CRITICAL"),
    Expression.color(Color.parseColor(COLOR_DEFAULT)),
)

/**
 * Filter expression for a specific geometry type.
 */
private fun geometryFilter(type: String): Expression = Expression.eq(
    Expression.get("geometryType"),
    Expression.literal(type),
)

/**
 * Renders traffic events on the MapLibre map as colored markers and lines.
 */
@Composable
fun TrafficMapOverlay(
    events: List<LiveTrafficEvent>,
    mapLibreMap: MapLibreMap?,
) {
    if (mapLibreMap == null) return

    val features = remember(events) {
        events.mapNotNull { it.toGeoJsonFeature() }
    }

    LaunchedEffect(features, mapLibreMap) {
        val style = mapLibreMap.style ?: return@LaunchedEffect

        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
        if (source != null) {
            source.setGeoJson(FeatureCollection.fromFeatures(features))
        } else {
            style.addSource(
                GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(features))
            )

            // Points: roadworks, incidents
            style.addLayer(
                CircleLayer(POINTS_LAYER, SOURCE_ID)
                    .withProperties(
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleColor(severityColorExpr()),
                        PropertyFactory.circleOpacity(0.8f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(
                            Expression.color(Color.WHITE)
                        ),
                    )
                    .withFilter(geometryFilter("Point"))
            )

            // Lines: closures, detours
            style.addLayer(
                LineLayer(LINES_LAYER, SOURCE_ID)
                    .withProperties(
                        PropertyFactory.lineColor(severityColorExpr()),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineOpacity(0.8f),
                    )
                    .withFilter(geometryFilter("LineString"))
            )

            // Labels: event descriptions
            style.addLayerAbove(
                SymbolLayer(LABELS_LAYER, SOURCE_ID)
                    .withProperties(
                        PropertyFactory.textField(
                            Expression.get("description")
                        ),
                        PropertyFactory.textFont(TRAFFIC_LABEL_FONT),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textColor(Color.BLACK),
                        PropertyFactory.textHaloColor(Color.WHITE),
                        PropertyFactory.textHaloWidth(1f),
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.textIgnorePlacement(false),
                    )
                    .withFilter(geometryFilter("Point")),
                POINTS_LAYER,
            )
        }
    }

    DisposableEffect(mapLibreMap) {
        onDispose {
            mapLibreMap.style?.let { style ->
                style.getLayer(LABELS_LAYER)?.let { style.removeLayer(it) }
                style.getLayer(LINES_LAYER)?.let { style.removeLayer(it) }
                style.getLayer(POINTS_LAYER)?.let { style.removeLayer(it) }
                style.getSource(SOURCE_ID)?.let { style.removeSource(it) }
            }
        }
    }
}

private fun LiveTrafficEvent.toGeoJsonFeature(): Feature? {
    val feature = when (val geom = geometry) {
        is TrafficGeometry.Point -> {
            Feature.fromGeometry(
                Point.fromLngLat(geom.longitude, geom.latitude)
            )
        }
        is TrafficGeometry.LineString -> {
            val points = geom.coordinates.map { (lon, lat) ->
                Point.fromLngLat(lon, lat)
            }
            if (points.size >= 2) {
                Feature.fromGeometry(LineString.fromLngLats(points))
            } else return null
        }
    }

    feature.addStringProperty("id", primaryKey)
    feature.addStringProperty("type", type.name)
    feature.addStringProperty("severity", severity.name)
    feature.addStringProperty("description", description.take(100))
    feature.addStringProperty(
        "geometryType",
        when (geometry) {
            is TrafficGeometry.Point -> "Point"
            is TrafficGeometry.LineString -> "LineString"
        },
    )

    return feature
}
