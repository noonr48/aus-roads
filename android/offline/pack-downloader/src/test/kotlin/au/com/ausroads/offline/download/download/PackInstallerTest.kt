package au.com.ausroads.offline.download.download

import android.content.Context
import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.offline.pack.OsmSource
import au.com.ausroads.offline.pack.PackComponents
import au.com.ausroads.offline.pack.PackManifest
import au.com.ausroads.offline.pack.RoutingComponent
import au.com.ausroads.offline.pack.SearchComponent
import au.com.ausroads.offline.pack.TileComponent
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PackInstallerTest {

    private lateinit var tempDir: File
    private lateinit var packStateStore: PackStateStore
    private lateinit var installer: PackInstaller
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("pack-installer-test")
        // Real PackStateStore against a temp filesDir so we exercise the actual
        // current.json write + cleanupIfEmpty file-system behaviour.
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        packStateStore = PackStateStore(context)
        baseDir = File(tempDir, "mappacks/au-sa")
        installer = PackInstaller(
            packVerifier = PackVerifier(),
            packStateStore = packStateStore,
            evictionManager = EvictionManager(packStateStore),
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `success with null manifest writes current json`() = runTest {
        val installDir = File(baseDir, "v2026-06-01").apply { mkdirs() }
        File(installDir, "tiles.mbtiles").writeText("tiles")

        val outcome = installer.finalize(
            installDir = installDir,
            packVersion = "2026-06-01",
            manifest = null,
            manifestJson = null,
            zipLength = 123L,
        )

        assertThat(outcome).isEqualTo(PackInstaller.Outcome.Installed)
        val current = packStateStore.readCurrent()
        assertThat(current).isNotNull()
        assertThat(current!!.version).isEqualTo("2026-06-01")
        assertThat(File(baseDir, "current.json").exists()).isTrue()
    }

    @Test
    fun `success with matching hashes writes current json`() = runTest {
        val installDir = File(baseDir, "v1").apply { mkdirs() }
        val tiles = File(installDir, "tiles.mbtiles").apply { writeText("real-tile-bytes") }
        val manifest = manifestWithTilesHash(sha256(tiles))

        val outcome = installer.finalize(
            installDir = installDir,
            packVersion = "1",
            manifest = manifest,
            manifestJson = "{}",
            zipLength = 0L,
        )

        assertThat(outcome).isEqualTo(PackInstaller.Outcome.Installed)
        val current = packStateStore.readCurrent()
        assertThat(current?.version).isEqualTo("1")
        // regionCode mirrors Region(country="AU", state="sa") verbatim, as the
        // original worker built it ("${country}-${state}").
        assertThat(current?.regionCode).isEqualTo("AU-sa")
        assertThat(installDir.exists()).isTrue()
    }

    @Test
    fun `verification failure deletes install dir and cleans empty base dir`() = runTest {
        val installDir = File(baseDir, "v1").apply { mkdirs() }
        File(installDir, "tiles.mbtiles").writeText("actual-bytes")
        // Manifest declares a hash that won't match the file on disk.
        val manifest = manifestWithTilesHash("deadbeef")

        val outcome = installer.finalize(
            installDir = installDir,
            packVersion = "1",
            manifest = manifest,
            manifestJson = "{}",
            zipLength = 0L,
        )

        assertThat(outcome).isInstanceOf(PackInstaller.Outcome.VerificationFailed::class.java)
        // Install dir removed, no current.json written, and the now-empty base dir
        // is cleaned up so a failed first download leaves nothing behind.
        assertThat(installDir.exists()).isFalse()
        assertThat(File(baseDir, "current.json").exists()).isFalse()
        assertThat(baseDir.exists()).isFalse()
    }

    @Test
    fun `verification failure keeps base dir when another pack is installed`() = runTest {
        // Pre-existing installed pack: current.json + its v dir.
        val keepDir = File(baseDir, "vkeep").apply { mkdirs() }
        File(keepDir, "tiles.mbtiles").writeText("kept")
        File(baseDir, "current.json").writeText(
            """{"version":"keep","regionCode":"AU-SA","installedAt":"2026-01-01T00:00:00Z",""" +
                """"totalSizeBytes":1,"tilesPath":"tiles.mbtiles","manifestSha256":"x"}""",
        )

        val installDir = File(baseDir, "v1").apply { mkdirs() }
        File(installDir, "tiles.mbtiles").writeText("bad")
        val manifest = manifestWithTilesHash("deadbeef")

        installer.finalize(
            installDir = installDir,
            packVersion = "1",
            manifest = manifest,
            manifestJson = "{}",
            zipLength = 0L,
        )

        // Failed install removed, but the base dir + existing pack survive.
        assertThat(installDir.exists()).isFalse()
        assertThat(baseDir.exists()).isTrue()
        assertThat(File(baseDir, "current.json").exists()).isTrue()
        assertThat(keepDir.exists()).isTrue()
    }

    private fun manifestWithTilesHash(hash: String) = PackManifest(
        packVersion = "1",
        region = Region.AU_SA,
        bbox = Bbox(129.0, -38.0, 141.0, -26.0),
        generatedAt = Instant.parse("2026-06-01T00:00:00Z"),
        osmSource = OsmSource("geofabrik", "https://example.com", Instant.parse("2026-05-30T00:00:00Z")),
        minAppVersion = "0.1.0",
        minAndroidSdk = 26,
        components = PackComponents(
            tiles = TileComponent("mbtiles", "openmaptiles", 0, 14, "tiles.mbtiles", 100L, hash),
            // routing format=none must be skipped by the verifier (tiles+search pack).
            routing = RoutingComponent("none", "none", "n/a", 0L, "none"),
            search = SearchComponent("none", "n/a", 0L, "none"),
        ),
        totalSizeBytes = 100L,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
}
