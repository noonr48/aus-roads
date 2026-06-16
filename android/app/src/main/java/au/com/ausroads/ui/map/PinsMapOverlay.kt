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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

const val PINS_SOURCE_ID = "saved-pins"
const val PINS_POINTS_LAYER = "saved-pins-points"
private const val PINS_LABELS_LAYER = "saved-pins-labels"

private const val PIN_FALLBACK_COLOR = "#1B5E20"

// Must match a font stack that exists under app/src/main/assets/glyphs/. The base
// map's label layers use exactly this; omitting it defaults to a stack including an
// unbundled font, which fails glyph loading and aborts the whole render frame
// (taking the marker circles down with it).
private val PIN_LABEL_FONT = arrayOf("Open Sans Regular")

/**
 * Renders saved pins on the MapLibre map as per-pin coloured markers with name
 * labels. Tapping a pin is handled in MapScreen, which hit-tests [PINS_POINTS_LAYER]
 * and reads the feature's "id" property.
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
                addStringProperty("color", pin.color.ifBlank { PIN_FALLBACK_COLOR })
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
            style.addLayer(
                CircleLayer(PINS_POINTS_LAYER, PINS_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(10f),
                    // MapLibre 11.5.2 renders a data-driven circleColor from a feature
                    // property as an invisible fill (verified); only a constant colour
                    // draws. Per-pin colour is shown in the pin list + detail sheet.
                    PropertyFactory.circleColor(Color.parseColor(PIN_FALLBACK_COLOR)),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(Expression.color(Color.WHITE)),
                ),
            )
            style.addLayerAbove(
                SymbolLayer(PINS_LABELS_LAYER, PINS_SOURCE_ID).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(PIN_LABEL_FONT),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor(Color.parseColor("#14171A")),
                    PropertyFactory.textHaloColor(Color.WHITE),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textAnchor("top"),
                    PropertyFactory.textOffset(arrayOf(0f, 1.0f)),
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textOptional(true),
                ),
                PINS_POINTS_LAYER,
            )
        }
    }

    DisposableEffect(mapLibreMap) {
        onDispose {
            mapLibreMap.style?.let { style ->
                style.getLayer(PINS_LABELS_LAYER)?.let { style.removeLayer(it) }
                style.getLayer(PINS_POINTS_LAYER)?.let { style.removeLayer(it) }
                style.getSource(PINS_SOURCE_ID)?.let { style.removeSource(it) }
            }
        }
    }
}
