/*
 * Schema-stability tests for PackManifest. These are the regression tests that catch a
 * silent breakage: someone removes the schemaVersion default, or someone adds a new
 * required field. Both break the on-the-wire contract for the map-pack downloader.
 */
package au.com.ausroads.offline.pack

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class PackManifestJsonTest {

    @Test
    fun `default schemaVersion is 1`() {
        val manifest = sampleManifest()

        assertThat(manifest.schemaVersion).isEqualTo(1)
    }

    @Test
    fun `default license is ODbL-1-0`() {
        val manifest = sampleManifest()

        assertThat(manifest.license).isEqualTo("ODbL-1.0")
    }

    @Test
    fun `default signatures is empty map`() {
        val manifest = sampleManifest()

        assertThat(manifest.signatures).isEmpty()
    }

    @Test
    fun `json missing signatures deserializes to empty map default`() {
        val jsonWithoutSignatures = """
            {
              "packVersion": "2026-06-01",
              "region": { "country": "AU", "state": "sa" },
              "bbox": { "west": 129.0, "south": -38.2, "east": 141.1, "north": -25.9 },
              "generatedAt": "2026-06-01T00:00:00Z",
              "osmSource": {
                "provider": "geofabrik",
                "url": "https://example.com/australia.osm.pbf",
                "osmExtractDate": "2026-05-30T12:00:00Z"
              },
              "minAppVersion": "0.1.0",
              "minAndroidSdk": 26,
              "components": {
                "tiles": {
                  "format": "mbtiles",
                  "schema": "tms",
                  "minZoom": 0,
                  "maxZoom": 14,
                  "path": "tiles/sa.mbtiles",
                  "sizeBytes": 1,
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                },
                "routing": {
                  "format": "valhalla-graphs",
                  "profile": "auto",
                  "path": "routing/sa.tar",
                  "sizeBytes": 1,
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                },
                "search": {
                  "format": "photon",
                  "path": "search/sa.photon.json",
                  "sizeBytes": 1,
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                }
              },
              "totalSizeBytes": 3
            }
        """.trimIndent()

        val decoded = Json.decodeFromString<PackManifest>(jsonWithoutSignatures)

        assertThat(decoded.signatures).isEmpty()
        assertThat(decoded.schemaVersion).isEqualTo(1)
        assertThat(decoded.license).isEqualTo("ODbL-1.0")
    }

    @Test
    fun `json with explicit schemaVersion preserves it`() {
        val json = """
            {
              "schemaVersion": 2,
              "packVersion": "2026-07-01",
              "region": { "country": "AU", "state": "sa" },
              "bbox": { "west": 129.0, "south": -38.2, "east": 141.1, "north": -25.9 },
              "generatedAt": "2026-07-01T00:00:00Z",
              "osmSource": {
                "provider": "geofabrik",
                "url": "https://example.com/australia.osm.pbf",
                "osmExtractDate": "2026-06-30T12:00:00Z"
              },
              "minAppVersion": "0.2.0",
              "minAndroidSdk": 26,
              "components": {
                "tiles": {
                  "format": "mbtiles",
                  "schema": "tms",
                  "minZoom": 0,
                  "maxZoom": 14,
                  "path": "tiles/sa.mbtiles",
                  "sizeBytes": 1,
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                },
                "routing": {
                  "format": "valhalla-graphs",
                  "profile": "auto",
                  "path": "routing/sa.tar",
                  "sizeBytes": 1,
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                },
                "search": {
                  "format": "photon",
                  "path": "search/sa.photon.json",
                  "sizeBytes": 1,
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                }
              },
              "totalSizeBytes": 3
            }
        """.trimIndent()

        val decoded = Json.decodeFromString<PackManifest>(json)

        assertThat(decoded.schemaVersion).isEqualTo(2)
        assertThat(decoded.packVersion).isEqualTo("2026-07-01")
    }

    @Test
    fun `with encodeDefaults, default values are present in serialized JSON`() {
        val wireJson = Json { encodeDefaults = true }
        val manifest = sampleManifest()
        val json = wireJson.encodeToString(manifest)

        assertThat(json).contains("\"schemaVersion\":1")
        assertThat(json).contains("\"license\":\"ODbL-1.0\"")
        assertThat(json).contains("\"signatures\":{}")
    }

    private fun sampleManifest(): PackManifest = PackManifest(
        packVersion = "2026-06-01",
        region = Region.AU_SA,
        bbox = Bbox.AUSTRALIA_SA,
        generatedAt = Instant.parse("2026-06-01T00:00:00Z"),
        osmSource = OsmSource(
            provider = "geofabrik",
            url = "https://example.com/australia.osm.pbf",
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
                sizeBytes = 1,
                sha256 = "0".repeat(64),
            ),
            routing = RoutingComponent(
                format = "valhalla-graphs",
                profile = "auto",
                path = "routing/sa.tar",
                sizeBytes = 1,
                sha256 = "0".repeat(64),
            ),
            search = SearchComponent(
                format = "photon",
                path = "search/sa.photon.json",
                sizeBytes = 1,
                sha256 = "0".repeat(64),
            ),
        ),
        totalSizeBytes = 3,
    )
}
