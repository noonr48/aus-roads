package au.com.ausroads.ui.map

import android.graphics.Color
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val USER_SOURCE_ID = "user-location"
private const val USER_HALO_LAYER = "user-location-halo"
private const val USER_DOT_LAYER = "user-location-dot"

// One blue, reserved for "you" — distinct from saved-pin green and traffic hues.
private const val USER_BLUE = "#1E88E5"

private const val TAG = "UserLocationOverlay"

/**
 * Renders the user's current position as a blue dot with a translucent halo and
 * white stroke (the conventional "you are here" puck). Fed by a live location
 * stream in MapScreen; drawn above all other overlays. Removed when [location]
 * is null (permission revoked / no fix) or on dispose.
 */
@Composable
fun UserLocationOverlay(
    latitude: Double?,
    longitude: Double?,
    mapLibreMap: MapLibreMap?,
) {
    if (mapLibreMap == null) return

    LaunchedEffect(latitude, longitude, mapLibreMap) {
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

        Log.d(TAG, "drawing blue dot at $lat,$lon")
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
                    PropertyFactory.circleRadius(18f),
                    PropertyFactory.circleColor(Expression.color(Color.parseColor(USER_BLUE))),
                    PropertyFactory.circleOpacity(0.15f),
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
