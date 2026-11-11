package au.com.ausroads.ui.map

import android.graphics.Color
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.pow

private const val USER_SOURCE_ID = "user-location"
internal const val USER_HALO_LAYER = "user-location-halo"
private const val USER_DOT_LAYER = "user-location-dot"

// One blue, reserved for "you" — distinct from saved-pin green and traffic hues.
private const val USER_BLUE = "#1E88E5"
private const val MIN_HALO_RADIUS_PX = 18f
private const val MAX_HALO_RADIUS_PX = 120f
private const val DEFAULT_APPROXIMATE_ACCURACY_METERS = 2_000f
private const val WEB_MERCATOR_METERS_PER_PIXEL_AT_ZOOM_ZERO = 156_543.03392
private const val PRECISE_HALO_OPACITY = 0.15f
private const val APPROXIMATE_HALO_OPACITY = 0.11f
private const val WEB_MERCATOR_MIN_LATITUDE = -85.0
private const val WEB_MERCATOR_MAX_LATITUDE = 85.0
private const val MIN_SUPPORTED_ZOOM = 0.0
private const val MAX_SUPPORTED_ZOOM = 24.0

private const val TAG = "UserLocationOverlay"

/**
 * Renders the user's current position as a blue dot with a translucent halo and
 * white stroke (the conventional "you are here" puck). The halo represents the
 * reported accuracy at the current zoom, so an approximate fix cannot look like
 * a precise pinpoint. Fed by a live location stream in MapScreen; drawn above
 * all other overlays. Removed when the coordinates are null (permission revoked
 * / no fix) or on dispose.
 */
@Composable
fun UserLocationOverlay(
    latitude: Double?,
    longitude: Double?,
    accuracyMeters: Float?,
    isPreciseLocationGranted: Boolean,
    mapLibreMap: MapLibreMap?,
) {
    if (mapLibreMap == null) return

    LaunchedEffect(
        latitude,
        longitude,
        accuracyMeters,
        isPreciseLocationGranted,
        mapLibreMap,
    ) {
        val style = mapLibreMap.style ?: return@LaunchedEffect
        val lat = latitude
        val lon = longitude

        if (lat == null || lon == null) {
            // No fix: clear the source so the dot disappears.
            Log.i(TAG, "no location — clearing blue dot")
            style.getSourceAs<GeoJsonSource>(USER_SOURCE_ID)
                ?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }

        Log.d(TAG, "drawing blue location dot")
        val collection = FeatureCollection.fromFeatures(
            listOf(Feature.fromGeometry(Point.fromLngLat(lon, lat))),
        )
        val source = style.getSourceAs<GeoJsonSource>(USER_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(collection)
        } else {
            style.addSource(GeoJsonSource(USER_SOURCE_ID, collection))
            style.addLayer(
                CircleLayer(USER_HALO_LAYER, USER_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(MIN_HALO_RADIUS_PX),
                    PropertyFactory.circleColor(Expression.color(Color.parseColor(USER_BLUE))),
                    PropertyFactory.circleOpacity(PRECISE_HALO_OPACITY),
                ),
            )
            style.addLayerAbove(
                CircleLayer(USER_DOT_LAYER, USER_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(Expression.color(Color.parseColor(USER_BLUE))),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor(Expression.color(Color.WHITE)),
                ),
                USER_HALO_LAYER,
            )
        }
        updateAccuracyHalo(
            mapLibreMap = mapLibreMap,
            latitude = lat,
            accuracyMeters = accuracyMeters,
            isPreciseLocationGranted = isPreciseLocationGranted,
        )
    }

    DisposableEffect(
        mapLibreMap,
        latitude,
        accuracyMeters,
        isPreciseLocationGranted,
    ) {
        val listener = MapLibreMap.OnCameraIdleListener {
            latitude?.let { lat ->
                updateAccuracyHalo(
                    mapLibreMap = mapLibreMap,
                    latitude = lat,
                    accuracyMeters = accuracyMeters,
                    isPreciseLocationGranted = isPreciseLocationGranted,
                )
            }
        }
        mapLibreMap.addOnCameraIdleListener(listener)
        onDispose { mapLibreMap.removeOnCameraIdleListener(listener) }
    }

    DisposableEffect(mapLibreMap) {
        onDispose {
            mapLibreMap.style?.let { style ->
                style.getLayer(USER_DOT_LAYER)?.let { style.removeLayer(it) }
                style.getLayer(USER_HALO_LAYER)?.let { style.removeLayer(it) }
                style.getSource(USER_SOURCE_ID)?.let { style.removeSource(it) }
            }
        }
    }
}

private fun updateAccuracyHalo(
    mapLibreMap: MapLibreMap,
    latitude: Double,
    accuracyMeters: Float?,
    isPreciseLocationGranted: Boolean,
) {
    val halo = mapLibreMap.style?.getLayer(USER_HALO_LAYER) as? CircleLayer ?: return
    halo.setProperties(
        PropertyFactory.circleRadius(
            locationAccuracyHaloRadiusPixels(
                accuracyMeters = accuracyMeters,
                latitude = latitude,
                zoom = mapLibreMap.cameraPosition.zoom,
                isPreciseLocationGranted = isPreciseLocationGranted,
            ),
        ),
        PropertyFactory.circleOpacity(
            if (isPreciseLocationGranted) PRECISE_HALO_OPACITY else APPROXIMATE_HALO_OPACITY,
        ),
    )
}

/** Convert reported accuracy to a zoom-aware, bounded on-screen uncertainty halo. */
internal fun locationAccuracyHaloRadiusPixels(
    accuracyMeters: Float?,
    latitude: Double,
    zoom: Double,
    isPreciseLocationGranted: Boolean,
): Float {
    val validAccuracy = accuracyMeters
        ?.takeIf { it.isFinite() && it >= 0f }
        ?: if (isPreciseLocationGranted) return MIN_HALO_RADIUS_PX
        else DEFAULT_APPROXIMATE_ACCURACY_METERS
    val safeLatitude = latitude.coerceIn(WEB_MERCATOR_MIN_LATITUDE, WEB_MERCATOR_MAX_LATITUDE)
    val safeZoom = zoom.coerceIn(MIN_SUPPORTED_ZOOM, MAX_SUPPORTED_ZOOM)
    val metersPerPixel = WEB_MERCATOR_METERS_PER_PIXEL_AT_ZOOM_ZERO *
        cos(Math.toRadians(safeLatitude)) / 2.0.pow(safeZoom)
    if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0) return MIN_HALO_RADIUS_PX
    return (validAccuracy / metersPerPixel)
        .toFloat()
        .coerceIn(MIN_HALO_RADIUS_PX, MAX_HALO_RADIUS_PX)
}

/** Keep every later-added overlay below the user puck when it exists. */
internal fun Style.addLayerBelowUser(layer: Layer) {
    if (getLayer(USER_HALO_LAYER) != null) addLayerBelow(layer, USER_HALO_LAYER)
    else addLayer(layer)
}
