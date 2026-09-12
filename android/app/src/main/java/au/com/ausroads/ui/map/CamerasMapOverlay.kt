package au.com.ausroads.ui.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import au.com.ausroads.offline.search.SpeedCameraPoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val SOURCE_ID = "speed-cameras"
private const val POINTS_LAYER = "cameras-points"
private const val LABELS_LAYER = "cameras-labels"

/** Marker bodies appear at city scale; labels one zoom step later. */
private const val CAMERAS_MIN_ZOOM = 11f
private const val CAMERAS_LABEL_MIN_ZOOM = 12f

// Must be a font stack bundled under app/src/main/assets/glyphs/ (see
// TrafficMapOverlay for the missing-glyph render-frame trap).
private val CAMERA_LABEL_FONT = arrayOf("Open Sans Regular")

/** Dark navy marker body — distinct from traffic severity hues and pin colours. */
private const val COLOR_CAMERA_BODY = "#263238"

/**
 * Renders fixed speed cameras on the MapLibre map: a dark disc with a white
 * ring and a red core, plus an optional enforced-limit label above the marker.
 *
 * Layers start at zoom 11 (city scale) so the state-wide view stays calm.
 */
@Composable
fun CamerasMapOverlay(
    cameras: List<SpeedCameraPoint>,
    mapLibreMap: MapLibreMap?,
) {
    if (mapLibreMap == null) return

    val features = remember(cameras) {
        cameras.map { it.toGeoJsonFeature() }
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

            // Marker body: dark disc with white stroke.
            style.addLayer(
                CircleLayer(POINTS_LAYER, SOURCE_ID)
                    .withProperties(
                        PropertyFactory.circleRadius(6f),
                        PropertyFactory.circleColor(Color.parseColor(COLOR_CAMERA_BODY)),
                        PropertyFactory.circleOpacity(0.9f),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                    )
                    .also { it.minZoom = CAMERAS_MIN_ZOOM }
            )

            // Enforced-limit label above the marker (blank when unknown).
            style.addLayer(
                SymbolLayer(LABELS_LAYER, SOURCE_ID)
                    .withProperties(
                        PropertyFactory.textField(Expression.get("limit")),
                        PropertyFactory.textFont(CAMERA_LABEL_FONT),
                        PropertyFactory.textSize(11f),
                        PropertyFactory.textColor(Color.WHITE),
                        PropertyFactory.textHaloColor(Color.BLACK),
                        PropertyFactory.textHaloWidth(1f),
                        PropertyFactory.textOffset(arrayOf(0f, -1.4f)),
                        PropertyFactory.textAllowOverlap(false),
                    )
                    .also { it.minZoom = CAMERAS_LABEL_MIN_ZOOM }
            )
        }
    }

    DisposableEffect(mapLibreMap) {
        onDispose {
            mapLibreMap.style?.let { style ->
                style.getLayer(LABELS_LAYER)?.let { style.removeLayer(it) }
                style.getLayer(POINTS_LAYER)?.let { style.removeLayer(it) }
                style.getSource(SOURCE_ID)?.let { style.removeSource(it) }
            }
        }
    }
}

private fun SpeedCameraPoint.toGeoJsonFeature(): Feature {
    val feature = Feature.fromGeometry(
        Point.fromLngLat(longitude, latitude)
    )
    feature.addStringProperty(
        "id",
        "%.6f,%.6f".format(java.util.Locale.US, latitude, longitude),
    )
    feature.addStringProperty(
        "limit",
        maxspeedKmh?.toString().orEmpty(),
    )
    return feature
}
