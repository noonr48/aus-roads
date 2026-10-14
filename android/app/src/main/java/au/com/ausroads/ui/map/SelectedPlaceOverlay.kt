package au.com.ausroads.ui.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val SOURCE = "selected-place"
private const val HALO = "selected-place-halo"
private const val DOT = "selected-place-dot"
internal const val SELECTED_PLACE_KEY = "place_key"

/** Exact catalog input; native tiled query geometry is a separate representation. */
internal fun selectedPlaceFeature(place: SearchResult): Feature =
    Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude)).apply {
        addStringProperty(SELECTED_PLACE_KEY, place.placeKey())
    }

/** A destination is visible at every zoom, independent of baked POI label minzoom. */
@Composable
fun SelectedPlaceOverlay(place: SearchResult?, map: MapLibreMap?) {
    if (map == null) return
    LaunchedEffect(place, map) {
        val style = map.style ?: return@LaunchedEffect
        val features =
            place?.let {
                arrayOf(selectedPlaceFeature(it))
            } ?: emptyArray()
        val collection = FeatureCollection.fromFeatures(features)
        val existing = style.getSourceAs<GeoJsonSource>(SOURCE)
        if (existing != null) existing.setGeoJson(collection)
        else {
            style.addSource(GeoJsonSource(SOURCE, collection))
            style.addLayer(
                CircleLayer(HALO, SOURCE)
                    .withProperties(
                        PropertyFactory.circleRadius(20f),
                        PropertyFactory.circleColor(Color.parseColor("#FFB300")),
                        PropertyFactory.circleOpacity(0.25f),
                    )
            )
            style.addLayer(
                CircleLayer(DOT, SOURCE)
                    .withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor(Color.parseColor("#FFB300")),
                        PropertyFactory.circleStrokeWidth(2f),
                        PropertyFactory.circleStrokeColor(Color.parseColor("#263238")),
                    )
            )
        }
    }
    DisposableEffect(map) {
        onDispose {
            map.style?.let { style ->
                style.getLayer(DOT)?.let { style.removeLayer(it) }
                style.getLayer(HALO)?.let { style.removeLayer(it) }
                style.getSource(SOURCE)?.let { style.removeSource(it) }
            }
        }
    }
}
