package au.com.ausroads.ui.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import au.com.ausroads.data.pins.Pin
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

const val PINS_SOURCE_ID = "saved-pins"
const val PINS_POINTS_LAYER = "saved-pins-points"

internal const val PIN_FALLBACK_COLOR = "#1B5E20"

private val PIN_RENDER_COLORS = setOf(
    PIN_FALLBACK_COLOR,
    "#00838F",
    "#6D4C41",
    "#C2185B",
    "#455A64",
    "#283593",
)

/**
 * Keeps map styling on the user-selectable palette. Older or malformed database
 * values cannot make a marker disappear or turn into a traffic-severity colour.
 */
internal fun renderablePinColor(color: String): String {
    val normalized = color.trim().uppercase()
    return normalized.takeIf(PIN_RENDER_COLORS::contains) ?: PIN_FALLBACK_COLOR
}

/**
 * MapLibre 11.5.2 needs each feature value resolved to a typed colour expression;
 * passing the raw GeoJSON string property directly renders an invisible fill.
 * This mirrors the proven severity match expression in [TrafficMapOverlay].
 */
private fun pinColorExpression(): Expression = Expression.match(
    Expression.get("color"),
    Expression.literal("#00838F"),
    Expression.color(Color.parseColor("#00838F")),
    Expression.literal("#6D4C41"),
    Expression.color(Color.parseColor("#6D4C41")),
    Expression.literal("#C2185B"),
    Expression.color(Color.parseColor("#C2185B")),
    Expression.literal("#455A64"),
    Expression.color(Color.parseColor("#455A64")),
    Expression.literal("#283593"),
    Expression.color(Color.parseColor("#283593")),
    Expression.color(Color.parseColor(PIN_FALLBACK_COLOR)),
)

/**
 * Renders saved pins on the MapLibre map as per-pin coloured markers. Tapping a
 * marker opens its named detail sheet; avoiding a second native symbol label keeps
 * it from colliding with the base map's own place labels.
 */
@Composable
fun PinsMapOverlay(
    pins: List<Pin>,
    mapLibreMap: MapLibreMap?,
) {
    if (mapLibreMap == null) return

    val features = remember(pins) {
        pins.map { pin ->
            Feature.fromGeometry(Point.fromLngLat(pin.lon, pin.lat)).apply {
                addStringProperty("id", pin.id.toString())
                addStringProperty("name", pin.name)
                addStringProperty("color", renderablePinColor(pin.color))
            }
        }
    }

    LaunchedEffect(features, mapLibreMap) {
        val style = mapLibreMap.style ?: return@LaunchedEffect
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(PINS_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(collection)
        } else {
            style.addSource(GeoJsonSource(PINS_SOURCE_ID, collection))
            style.addLayerBelowUser(
                CircleLayer(PINS_POINTS_LAYER, PINS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(10f),
                    PropertyFactory.circleColor(pinColorExpression()),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(Expression.color(Color.WHITE)),
                ),
            )
        }
    }

    DisposableEffect(mapLibreMap) {
        onDispose {
            mapLibreMap.style?.let { style ->
                style.getLayer(PINS_POINTS_LAYER)?.let { style.removeLayer(it) }
                style.getSource(PINS_SOURCE_ID)?.let { style.removeSource(it) }
            }
        }
    }
}
