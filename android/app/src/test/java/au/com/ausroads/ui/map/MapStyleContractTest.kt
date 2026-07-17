package au.com.ausroads.ui.map

import au.com.ausroads.io.isExpectedSqliteDatabase
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class MapStyleContractTest {
    private val styleText = File("src/main/assets/maptile/style.json").readText()
    private val style = Json.parseToJsonElement(styleText).jsonObject
    private val layers = style.getValue("layers").jsonArray.associateBy {
        it.jsonObject.getValue("id").jsonPrimitive.content
    }

    @Test
    fun `bundled tiles match their checksum contract`() {
        val tiles = File("src/main/assets/maptile/adelaide-test-tiles.mbtiles")
        val expectedHash = File("${tiles.path}.sha256").readText().trim().substringBefore(' ')

        assertThat(isExpectedSqliteDatabase(tiles, expectedHash)).isTrue()
    }

    @Test
    fun `roads and labels keep scaling through maximum camera zoom`() {
        listOf("road-minor", "road-secondary", "road-primary", "road-motorway").forEach { id ->
            val fillStops = lineWidthStops(id)
            val casingStops = lineWidthStops("$id-casing")

            assertMonotonicallyIncreasing(fillStops)
            assertMonotonicallyIncreasing(casingStops)
            assertThat(fillStops.getValue(16.0)).isGreaterThan(fillStops.getValue(14.0))
            assertThat(casingStops.getValue(16.0)).isGreaterThan(casingStops.getValue(14.0))
            fillStops.keys.intersect(casingStops.keys).forEach { zoom ->
                assertThat(casingStops.getValue(zoom)).isGreaterThan(fillStops.getValue(zoom))
            }
        }

        val roadLabelStops = numericStops(
            layers.getValue("road-label").jsonObject
                .getValue("layout").jsonObject
                .getValue("text-size").jsonArray,
        )
        assertMonotonicallyIncreasing(roadLabelStops)
        assertThat(roadLabelStops.getValue(16.0)).isGreaterThan(roadLabelStops.getValue(14.0))
    }

    @Test
    fun `shops become visible at the first zoom containing POI data`() {
        val shopLayer = layers.getValue("poi-shop-dot").jsonObject

        assertThat(shopLayer.getValue("minzoom").jsonPrimitive.content.toInt()).isEqualTo(14)
        assertThat(rankCeiling("poi-shop-dot")).isEqualTo(20)
    }

    @Test
    fun `poi density stays bounded instead of revealing every feature at maximum zoom`() {
        assertThat(styleText).doesNotContain("poi-shop-dot-detail")
        assertThat(styleText).doesNotContain("poi-dot-detail")
        assertThat(styleText).doesNotContain("poi-detail")
        assertThat(rankCeiling("poi-shop-dot")).isEqualTo(20)
        assertThat(rankCeiling("poi-dot")).isEqualTo(8)
        assertThat(rankCeiling("poi")).isEqualTo(6)
    }

    @Test
    fun `style omits an empty sprite endpoint`() {
        assertThat(styleText).doesNotContain("\"sprite\": \"\"")
    }

    @Test
    fun `bundled font resolves every unicode range requested by local place names`() {
        val expectedHashes = mapOf(
            "0-255" to "0c8bdb284f2ad6f69bbb434a0208393f3a517bc8bf7ed1eccc4ac4791ebbcbfe",
            "256-511" to "9962ad38d064edbeb226a6c7dcf1d58e7678508a805f980d2fe81bb3178ba68a",
            "512-767" to "2a7c191141c66e209d08d834df530a06a234224d48ae97d69f1bde1b1b5db404",
            "768-1023" to "fea6a1c8e1be55d9725520627b108d425723b2dc189b7dddfe56abd7d8238ea9",
            "7680-7935" to "d83e773d45d475f5ffd25917cd8735450586bc9d42a53fb54ccce5618241d976",
            "8192-8447" to "a57611157f0597b267ef36eb07ce5a41dddcc3b2f3b5b85779108b7bb48b1cde",
            "9984-10239" to "bf288e9537f8710f2f899bda3e624732837d919de9a8e29b441c587142221dd2",
            "65024-65279" to "4a8cda576e7f10a063b984265d0055c3e3a4459c59cb50d0579bad71de31b33c",
        )
        val directory = File("src/main/assets/glyphs/Open Sans Regular")

        expectedHashes.forEach { (range, expectedHash) ->
            val glyphs = File(directory, "$range.pbf")
            assertThat(glyphs.isFile).isTrue()
            assertThat(glyphs.sha256()).isEqualTo(expectedHash)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun lineWidthStops(layerId: String): Map<Double, Double> = numericStops(
        layers.getValue(layerId).jsonObject
            .getValue("paint").jsonObject
            .getValue("line-width").jsonArray,
    )

    private fun rankCeiling(layerId: String): Int {
        val filter = layers.getValue(layerId).jsonObject.getValue("filter").jsonArray
        val rankClause = filter.filterIsInstance<JsonArray>().single { clause ->
            clause.size == 3 &&
                clause[0].jsonPrimitive.content == "<=" &&
                clause[1].jsonPrimitive.content == "rank"
        }
        return rankClause[2].jsonPrimitive.content.toInt()
    }

    private fun numericStops(expression: JsonArray): Map<Double, Double> = expression
        .drop(3)
        .chunked(2)
        .associate { (zoom, width) ->
            zoom.jsonPrimitive.content.toDouble() to width.jsonPrimitive.content.toDouble()
        }

    private fun assertMonotonicallyIncreasing(stops: Map<Double, Double>) {
        val ordered = stops.toSortedMap().values.toList()
        assertThat(ordered.size).isAtLeast(2)
        ordered.zipWithNext().forEach { (before, after) ->
            assertThat(after).isGreaterThan(before)
        }
    }
}
