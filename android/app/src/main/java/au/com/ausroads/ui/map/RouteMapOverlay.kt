package au.com.ausroads.ui.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import au.com.ausroads.routing.engine.RouteResult
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE_ID = "route-line"
private const val ROUTE_LAYER_ID = "route-line"

@Composable
fun RouteMapOverlay(
    routeResult: RouteResult?,
    mapLibreMap: MapLibreMap?,
) {
    if (mapLibreMap == null) return

    LaunchedEffect(routeResult, mapLibreMap) {
        val style = mapLibreMap.style ?: return@LaunchedEffect
        val result = routeResult

        if (result == null) {
            style.getLayer(ROUTE_LAYER_ID)?.let { style.removeLayer(it) }
            style.getSource(ROUTE_SOURCE_ID)?.let { style.removeSource(it) }
            return@LaunchedEffect
        }

        val points = result.geometry.map { geo ->
            Point.fromLngLat(geo.longitude, geo.latitude)
        }
        if (points.size < 2) return@LaunchedEffect

        val feature = Feature.fromGeometry(LineString.fromLngLats(points))
        val featureCollection = org.maplibre.geojson.FeatureCollection.fromFeature(feature)

        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        if (source != null) {
            source.setGeoJson(featureCollection)
        } else {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, featureCollection))
            style.addLayer(
                LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID)
                    .withProperties(
                        PropertyFactory.lineColor(Color.parseColor("#2196F3")),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineOpacity(0.8f),
                    )
            )
        }
    }

    DisposableEffect(mapLibreMap) {
        onDispose {
            mapLibreMap.style?.let { style ->
                style.getLayer(ROUTE_LAYER_ID)?.let { style.removeLayer(it) }
                style.getSource(ROUTE_SOURCE_ID)?.let { style.removeSource(it) }
            }
        }
    }
}
