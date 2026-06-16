/*
 * PackManifest is the wire schema for the in-app map-pack downloader. Its JSON shape is
 * locked at v1; this test pins the round-trip so a future refactor that drops or renames
 * a field will fail the build.
 */
package au.com.ausroads.offline.pack

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class PackManifestTest {

    @Test
    fun `serializes and deserializes a populated manifest with all fields preserved`() {
        val original = sampleManifest()

        val json = Json.encodeToString(original)
        val decoded = Json.decodeFromString<PackManifest>(json)

        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.schemaVersion).isEqualTo(original.schemaVersion)
        assertThat(decoded.packVersion).isEqualTo("2026-06-01")
        assertThat(decoded.region).isEqualTo(Region.AU_SA)
        assertThat(decoded.bbox).isEqualTo(Bbox.AUSTRALIA_SA)
        assertThat(decoded.generatedAt).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        assertThat(decoded.osmSource.provider).isEqualTo("geofabrik")
        assertThat(decoded.license).isEqualTo("ODbL-1.0")
        assertThat(decoded.minAppVersion).isEqualTo("0.1.0")
        assertThat(decoded.minAndroidSdk).isEqualTo(26)
        assertThat(decoded.components.tiles.sha256).isEqualTo(original.components.tiles.sha256)
        assertThat(decoded.components.routing.sha256).isEqualTo(original.components.routing.sha256)
        assertThat(decoded.components.search.sha256).isEqualTo(original.components.search.sha256)
        assertThat(decoded.totalSizeBytes).isEqualTo(original.totalSizeBytes)
        assertThat(decoded.signatures).isEqualTo(original.signatures)
    }

    @Test
    fun `json with encodeDefaults contains expected field names (wire stability)`() {
        val wireJson = Json { encodeDefaults = true }
        val json = wireJson.encodeToString(sampleManifest())

        assertThat(json).contains("\"schemaVersion\"")
        assertThat(json).contains("\"packVersion\"")
        assertThat(json).contains("\"region\"")
        assertThat(json).contains("\"bbox\"")
        assertThat(json).contains("\"generatedAt\"")
        assertThat(json).contains("\"osmSource\"")
        assertThat(json).contains("\"license\"")
        assertThat(json).contains("\"minAppVersion\"")
        assertThat(json).contains("\"minAndroidSdk\"")
        assertThat(json).contains("\"components\"")
        assertThat(json).contains("\"totalSizeBytes\"")
        assertThat(json).contains("\"signatures\"")
        assertThat(json).contains("\"sha256\"")
    }

    @Test
    fun `equality holds for two manifests with identical fields`() {
        val a = sampleManifest()
        val b = sampleManifest()

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `decoded manifest preserves SHA-256 component hashes exactly`() {
        val original = sampleManifest()
        val decoded = Json.decodeFromString<PackManifest>(Json.encodeToString(original))

        assertThat(decoded.components.tiles.sha256).hasLength(64)
        assertThat(decoded.components.routing.sha256).hasLength(64)
        assertThat(decoded.components.search.sha256).hasLength(64)
    }

    private fun sampleManifest(): PackManifest = PackManifest(
        schemaVersion = 1,
        packVersion = "2026-06-01",
        region = Region.AU_SA,
        bbox = Bbox.AUSTRALIA_SA,
        generatedAt = Instant.parse("2026-06-01T00:00:00Z"),
        osmSource = OsmSource(
            provider = "geofabrik",
            url = "https://download.geofabrik.de/australia-oceania/australia-latest-free.shp.zip",
            osmExtractDate = Instant.parse("2026-05-30T12:00:00Z"),
        ),
        minAppVersion = "0.1.0",
        minAndroidSdk = 26,
        components = PackComponents(
            tiles = TileComponent(
                format = "mbtiles",
                schema = "tms",
                minZoom = 0,
                maxZoom = 14,
                path = "tiles/sa.mbtiles",
                sizeBytes = 123_456_789L,
                sha256 = "a".repeat(64),
            ),
            routing = RoutingComponent(
                format = "valhalla-graphs",
                profile = "auto",
                path = "routing/sa.tar",
                sizeBytes = 12_345_678L,
                sha256 = "b".repeat(64),
            ),
            search = SearchComponent(
                format = "photon",
                path = "search/sa.photon.json",
                sizeBytes = 1_234_567L,
                sha256 = "c".repeat(64),
            ),
        ),
        totalSizeBytes = 123_456_789L + 12_345_678L + 1_234_567L,
        signatures = mapOf("ed25519" to "d".repeat(128)),
    )
}
