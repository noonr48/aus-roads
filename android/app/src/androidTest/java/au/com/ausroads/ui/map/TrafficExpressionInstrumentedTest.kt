package au.com.ausroads.ui.map

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.MainActivity
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Real SDK/JNI properties and expression evaluation, not a JVM SDK substitute or replacement UI. */
@RunWith(AndroidJUnit4::class)
class TrafficExpressionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var restoreFixture: (() -> Unit)? = null

    @Before fun guestOnly() {
        check(Build.MODEL.startsWith("sdk_gphone")) // these fixtures must never run on the Pixel.
    }

    @After fun restoreOwnedLayers() {
        restoreFixture?.invoke()
        restoreFixture = null
    }

    @Test fun severityMatchHasFlatScalarLabelsAndExplicitDefault() {
        assertFlatMatch(severityColorExpr())
        println("NATIVE_TRAFFIC_STAGE case=severityMatchHasFlatScalarLabelsAndExplicitDefault stage=scalar-match-positive")
    }

    @Test fun severityColorsRoundTripAndEvaluateOnNativePointAndLineLayers() {
        val (map, view) = awaitNativeStyle()
        val cases = listOf("LOW", "MEDIUM", "HIGH", "CRITICAL", "UNKNOWN", "MISSING")
        val colors = listOf("#4CAF50", "#FF9800", "#F44336", "#9C27B0", "#FF9800", "#FF9800")
        val expression = severityColorExpr()
        val points = "traffic-expression-proof-points"
        val lines = "traffic-expression-proof-lines"
        val sourceId = "traffic-expression-proof-source"
        lateinit var rectangle: RectF
        var cameraUnchanged: (() -> Boolean)? = null
        val nativeExpressions = mutableMapOf<String, Expression>()
        compose.runOnUiThread {
            val style = requireNotNull(map.style)
            check(style.getSource(sourceId) == null && style.getLayer(points) == null && style.getLayer(lines) == null)
            val camera = map.cameraPosition
            cameraUnchanged = { map.cameraPosition == camera }
            // Install rollback BEFORE any JNI fixture mutation, including partial setup.
            restoreFixture = {
                compose.runOnUiThread {
                    style.getLayer(lines)?.let { style.removeLayer(it) }
                    style.getLayer(points)?.let { style.removeLayer(it) }
                    style.getSource(sourceId)?.let { style.removeSource(it) }
                }
            }
            rectangle = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
            val features = cases.flatMapIndexed { index, severity ->
                val x = view.width * (0.25f + index * 0.08f)
                fun point(y: Float): Point {
                    val coordinate = map.projection.fromScreenLocation(PointF(x, y))
                    return Point.fromLngLat(coordinate.longitude, coordinate.latitude)
                }
                val center = view.height * 0.5f
                listOf(
                    Feature.fromGeometry(point(center)).apply { addStringProperty("geometryType", "Point") },
                    Feature.fromGeometry(LineString.fromLngLats(listOf(point(center - 20f), point(center + 20f))))
                        .apply { addStringProperty("geometryType", "LineString") },
                ).onEach {
                    it.addStringProperty("fixture_case", severity)
                    if (severity != "MISSING") it.addStringProperty("severity", severity)
                }
            }
            style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(features)))
            style.addLayer(CircleLayer(points, sourceId).withProperties(
                PropertyFactory.circleRadius(8f), PropertyFactory.circleColor(expression),
            ).withFilter(Expression.eq(Expression.get("geometryType"), Expression.literal("Point"))))
            style.addLayer(LineLayer(lines, sourceId).withProperties(
                PropertyFactory.lineWidth(4f), PropertyFactory.lineColor(expression),
            ).withFilter(Expression.eq(Expression.get("geometryType"), Expression.literal("LineString"))))
            // Read back attached native layers, not just the original Kotlin objects.
            val nativePoints = requireNotNull(style.getLayer(points)) as CircleLayer
            val nativeLines = requireNotNull(style.getLayer(lines)) as LineLayer
            assertThat(nativePoints.circleColor.isExpression).isTrue()
            assertThat(nativeLines.lineColor.isExpression).isTrue()
            nativeExpressions[points] = requireNotNull(nativePoints.circleColor.expression)
            nativeExpressions[lines] = requireNotNull(nativeLines.lineColor.expression)
            nativeExpressions.values.forEach(::assertFlatMatch)
            map.triggerRepaint()
        }
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnUiThread {
                ready = listOf(points, lines).all { layer ->
                    map.queryRenderedFeatures(rectangle, layer).map { it.getStringProperty("fixture_case") }.toSet() == cases.toSet()
                }
            }
            ready
        }
        compose.runOnUiThread {
            for (layer in listOf(points, lines)) {
                // Native query filters evaluate the very expression installed as paint.
                // Duplicate tile copies are allowed; the exact fixture set is required.
                val nativeExpression = nativeExpressions.getValue(layer)
                for (color in colors.distinct()) {
                    val filter = colorFilter(nativeExpression, color)
                    val expected = cases.filterIndexed { index, _ -> colors[index] == color }.toSet()
                    assertThat(map.queryRenderedFeatures(rectangle, filter, layer)
                        .map { it.getStringProperty("fixture_case") }.toSet()).isEqualTo(expected)
                }
                val wrongLowColor = Expression.all(
                    Expression.eq(Expression.get("fixture_case"), Expression.literal("LOW")),
                    colorFilter(nativeExpression, "#F44336"),
                )
                assertThat(map.queryRenderedFeatures(rectangle, wrongLowColor, layer)).isEmpty()
            }
            assertThat(requireNotNull(cameraUnchanged).invoke()).isTrue()
            assertThat(findMap(compose.activity.window.decorView)).isSameInstanceAs(view)
        }
        println("NATIVE_TRAFFIC_STAGE case=severityColorsRoundTripAndEvaluateOnNativePointAndLineLayers stage=native-color-positive")
        println("NATIVE_TRAFFIC_STAGE case=severityColorsRoundTripAndEvaluateOnNativePointAndLineLayers stage=wrong-color-negative")
    }

    private fun assertFlatMatch(expression: Expression) {
        val array = JSONArray(expression.toString())
        assertThat(array.length()).isEqualTo(11)
        assertThat(array.getString(0)).isEqualTo("match")
        val input = array.getJSONArray(1)
        assertThat(input.length()).isEqualTo(2)
        assertThat(input.getString(0)).isEqualTo("get")
        assertThat(input.getString(1)).isEqualTo("severity")
        val expected = mapOf("LOW" to "#4CAF50", "MEDIUM" to "#FF9800",
            "HIGH" to "#F44336", "CRITICAL" to "#9C27B0")
        val outputs = mutableMapOf<String, Any>()
        // android-v11.6.1 Match::serialize sorts whole label/output pairs;
        // evaluation looks up the key. Pair identity is strict, not ordinal.
        for (index in 2..8 step 2) {
            assertThat(array.get(index) is String).isTrue()
            val label = array.getString(index)
            assertThat(outputs.containsKey(label)).isFalse()
            outputs[label] = array.get(index + 1)
        }
        assertThat(outputs.keys).containsExactlyElementsIn(expected.keys)
        expected.forEach { (label, hex) -> assertColor(outputs.getValue(label), hex) }
        assertColor(array.get(10), "#FF9800") // Exact fallback, including alpha.
    }

    private fun assertColor(output: Any, hex: String) {
        // JNI can serialize a constant color as rgba(...) or an rgba expression.
        val channels = when (output) {
            is JSONArray -> {
                assertThat(output.length()).isEqualTo(5)
                assertThat(output.getString(0)).isEqualTo("rgba")
                (1..4).map(output::getDouble)
            }
            is String -> {
                check(output.startsWith("rgba(") && output.endsWith(")"))
                output.removePrefix("rgba(").removeSuffix(")").split(',').map { it.trim().toDouble() }
            }
            else -> error("component=native-proof seam=traffic-color class=contract-drift")
        }
        val argb = Color.parseColor(hex)
        assertThat(channels).isEqualTo(listOf(Color.red(argb).toDouble(), Color.green(argb).toDouble(),
            Color.blue(argb).toDouble(), 1.0))
    }

    private fun colorFilter(expression: Expression, hex: String): Expression {
        // Native == does not accept Color/Array. Compare four legal scalars.
        // Round RGB to its specified 8-bit channels; alpha remains exact.
        val rgba = Expression.toRgba(expression)
        val argb = Color.parseColor(hex)
        return Expression.all(
            Expression.eq(Expression.round(Expression.at(0, rgba)), Color.red(argb)),
            Expression.eq(Expression.round(Expression.at(1, rgba)), Color.green(argb)),
            Expression.eq(Expression.round(Expression.at(2, rgba)), Color.blue(argb)),
            Expression.eq(Expression.at(3, rgba), 1.0),
        )
    }

    private fun awaitNativeStyle(): Pair<MapLibreMap, MapView> {
        var view: MapView? = null
        var map: MapLibreMap? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread { view = findMap(compose.activity.window.decorView) }
            view != null
        }
        val nativeView = requireNotNull(view)
        compose.runOnUiThread { nativeView.getMapAsync { map = it; it.triggerRepaint() } }
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnUiThread {
                ready = map?.style != null && nativeView.width > 0 && nativeView.height > 0 &&
                    nativeView.isAttachedToWindow && nativeView.hasWindowFocus() && nativeView.isShown &&
                    map?.queryRenderedFeatures(RectF(0f, 0f, nativeView.width.toFloat(), nativeView.height.toFloat()),
                        "road-minor", "road-secondary", "road-primary", "road-motorway", "road-label", "place-suburb")
                        ?.isNotEmpty() == true
            }
            ready
        }
        return requireNotNull(map) to nativeView
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
