package au.com.ausroads.ui.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import au.com.ausroads.offline.search.SearchResult
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val SOURCE_ID = "selected-search-result"
internal const val SELECTED_SEARCH_RESULT_HALO_LAYER_ID = "selected-search-result-halo"
internal const val SELECTED_SEARCH_RESULT_POINT_LAYER_ID = "selected-search-result-point"
private const val LABEL_LAYER_ID = "selected-search-result-label"
private const val RESULT_ORANGE = "#D94F30"
private const val POI_ZOOM = 15.5
private const val ROAD_ZOOM = 15.0
private const val SUBURB_ZOOM = 13.0
private const val AREA_ZOOM = 14.0
private const val DEFAULT_RESULT_ZOOM = 14.5
private val LABEL_FONT = arrayOf("Open Sans Regular")

/** Every visible selected-result circle participates in map hit testing. */
internal fun selectedSearchResultHitLayerIds(): Array<String> = arrayOf(
    SELECTED_SEARCH_RESULT_POINT_LAYER_ID,
    SELECTED_SEARCH_RESULT_HALO_LAYER_ID,
)

/** Draws an unmistakable marker for both search and Nearby selections. */
@Composable
internal fun SearchResultMapOverlay(
    result: SearchResult?,
    mapLibreMap: MapLibreMap?,
) {
    if (mapLibreMap == null) return

    LaunchedEffect(result, mapLibreMap) {
        val style = mapLibreMap.style ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
        if (result == null) {
            source?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }

        val feature = Feature.fromGeometry(Point.fromLngLat(result.longitude, result.latitude)).apply {
            addStringProperty("name", result.name)
        }
        val collection = FeatureCollection.fromFeature(feature)
        if (source != null) {
            source.setGeoJson(collection)
            return@LaunchedEffect
        }

        style.addSource(GeoJsonSource(SOURCE_ID, collection))
        style.addLayerBelowUser(
            CircleLayer(SELECTED_SEARCH_RESULT_HALO_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(16f),
                PropertyFactory.circleColor(Expression.color(Color.parseColor(RESULT_ORANGE))),
                PropertyFactory.circleOpacity(0.18f),
            ),
        )
        style.addLayerAbove(
            CircleLayer(SELECTED_SEARCH_RESULT_POINT_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(Expression.color(Color.parseColor(RESULT_ORANGE))),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(Expression.color(Color.WHITE)),
            ),
            SELECTED_SEARCH_RESULT_HALO_LAYER_ID,
        )
        style.addLayerAbove(
            SymbolLayer(LABEL_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(LABEL_FONT),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor(Color.parseColor("#14171A")),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAnchor("top"),
                PropertyFactory.textOffset(arrayOf(0f, 1.15f)),
                PropertyFactory.textAllowOverlap(true),
            ),
            SELECTED_SEARCH_RESULT_POINT_LAYER_ID,
        )
    }

    DisposableEffect(mapLibreMap) {
        onDispose {
            mapLibreMap.style?.let { style ->
                style.getLayer(LABEL_LAYER_ID)?.let(style::removeLayer)
                style.getLayer(SELECTED_SEARCH_RESULT_POINT_LAYER_ID)?.let(style::removeLayer)
                style.getLayer(SELECTED_SEARCH_RESULT_HALO_LAYER_ID)?.let(style::removeLayer)
                style.getSource(SOURCE_ID)?.let(style::removeSource)
            }
        }
    }
}

/** POIs must land above the base style's POI threshold; regions remain contextual. */
internal fun mapZoomForSearchResult(result: SearchResult): Double = when (result.kind.lowercase()) {
    "poi" -> POI_ZOOM
    "road" -> ROAD_ZOOM
    "suburb" -> SUBURB_ZOOM
    "park", "water" -> AREA_ZOOM
    else -> DEFAULT_RESULT_ZOOM
}
