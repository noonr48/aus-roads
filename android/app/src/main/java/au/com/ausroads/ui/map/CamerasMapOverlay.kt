package au.com.ausroads.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import au.com.ausroads.R
import au.com.ausroads.offline.search.SpeedCameraPoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
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

private const val CAMERA_MARKER_PX = 32
private const val CAMERA_ICON_SCALE = 1.1f

/**
 * Renders fixed speed cameras with a compact camera pictogram, plus an optional enforced-limit
 * label above the marker. Preserve city-scale camera taps.
 *
 * Layers start at zoom 11 (city scale) so the state-wide view stays calm.
 */
@Composable
fun CamerasMapOverlay(
    cameras: List<SpeedCameraPoint>,
    mapLibreMap: MapLibreMap?,
) {
    if (mapLibreMap == null) return

    val context = LocalContext.current
    val marker =
        remember(context) {
            Bitmap.createBitmap(CAMERA_MARKER_PX, CAMERA_MARKER_PX, Bitmap.Config.ARGB_8888).also {
                bitmap ->
                val drawable =
                    requireNotNull(ContextCompat.getDrawable(context, R.drawable.map_camera_marker))
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
            }
        }
    val features =
        remember(cameras) {
            cameras.map { it.toGeoJsonFeature() }
        }

    LaunchedEffect(features, mapLibreMap) {
        val style = mapLibreMap.style ?: return@LaunchedEffect

        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
        if (source != null) {
            source.setGeoJson(FeatureCollection.fromFeatures(features))
        } else {
            style.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(features)))

            // Preserve the tap layer/id/zoom, but distinguish cameras from saved-place dots.
            style.addImage("camera-marker", marker)
            style.addLayer(
                SymbolLayer(POINTS_LAYER, SOURCE_ID)
                    .withProperties(
                        PropertyFactory.iconImage("camera-marker"),
                        PropertyFactory.iconSize(CAMERA_ICON_SCALE),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
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
    val feature = Feature.fromGeometry(Point.fromLngLat(longitude, latitude))
    feature.addStringProperty(
        "id",
        mapFeatureId(),
    )
    feature.addStringProperty(
        "limit",
        maxspeedKmh?.toString().orEmpty(),
    )
    return feature
}
