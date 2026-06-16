package au.com.ausroads.offline.download.download

import au.com.ausroads.offline.pack.PackManifest
import au.com.ausroads.offline.pack.PackComponents
import au.com.ausroads.offline.pack.TileComponent
import au.com.ausroads.offline.pack.SearchComponent
import au.com.ausroads.offline.pack.RoutingComponent
import au.com.ausroads.offline.pack.OsmSource
import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import kotlinx.datetime.Instant
import org.junit.Before
import org.junit.Test
import org.junit.After
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest

class PackVerifierTest {

    private lateinit var tempDir: File
    private lateinit var verifier: PackVerifier

    @Before
    fun setUp() {
        tempDir = createTempDir("pack-verify-test")
        verifier = PackVerifier()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `verify returns Ok for matching SHA-256`() {
        val content = "test tile data"
        val tileFile = File(tempDir, "tiles.mbtiles")
        tileFile.writeText(content)
        val sha256 = sha256(content)

        val manifest = makeManifest(tilesSha256 = sha256, tilesPath = "tiles.mbtiles")
        val result = verifier.verify(tempDir, manifest)

        assertThat(result).isEqualTo(PackVerifier.VerificationResult.Ok)
    }

    @Test
    fun `verify returns Mismatch for wrong SHA-256`() {
        val tileFile = File(tempDir, "tiles.mbtiles")
        tileFile.writeText("actual data")

        val manifest = makeManifest(tilesSha256 = "wrong_hash", tilesPath = "tiles.mbtiles")
        val result = verifier.verify(tempDir, manifest)

        assertThat(result).isInstanceOf(PackVerifier.VerificationResult.Mismatch::class.java)
        val mismatch = result as PackVerifier.VerificationResult.Mismatch
        assertThat(mismatch.component).isEqualTo("tiles")
        assertThat(mismatch.expected).isEqualTo("wrong_hash")
    }

    @Test
    fun `verify returns Mismatch for missing file`() {
        val manifest = makeManifest(tilesSha256 = "abc", tilesPath = "nonexistent.mbtiles")
        val result = verifier.verify(tempDir, manifest)

        assertThat(result).isInstanceOf(PackVerifier.VerificationResult.Mismatch::class.java)
        val mismatch = result as PackVerifier.VerificationResult.Mismatch
        assertThat(mismatch.actual).isEqualTo("file_missing")
    }

    private fun sha256(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun makeManifest(tilesSha256: String, tilesPath: String) = PackManifest(
        schemaVersion = 1,
        packVersion = "test-1",
        region = Region("AU", "SA"),
        bbox = Bbox(138.0, -35.0, 139.0, -34.0),
        generatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        osmSource = OsmSource("geofabrik", "https://example.com", Instant.parse("2026-01-01T00:00:00Z")),
        license = "ODbL-1.0",
        minAppVersion = "1.0.0",
        minAndroidSdk = 26,
        components = PackComponents(
            tiles = TileComponent(
                format = "mbtiles",
                schema = "openmaptiles",
                minZoom = 0,
                maxZoom = 14,
                path = tilesPath,
                sizeBytes = 100,
                sha256 = tilesSha256,
            ),
            routing = RoutingComponent(
                format = "none",
                profile = "none",
                path = "n/a",
                sizeBytes = 0,
                sha256 = "none",
            ),
            search = SearchComponent(
                format = "none",
                path = "n/a",
                sizeBytes = 0,
                sha256 = "none",
            ),
        ),
        totalSizeBytes = 100,
        signatures = emptyMap(),
    )
}
