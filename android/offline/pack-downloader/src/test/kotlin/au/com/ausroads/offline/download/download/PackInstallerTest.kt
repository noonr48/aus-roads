package au.com.ausroads.offline.download.download

import android.content.Context
import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.offline.download.MapPackDownloadWorker
import au.com.ausroads.offline.download.workerFailureData
import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.state.DownloadProgress.Phase
import au.com.ausroads.offline.download.state.InstalledPack
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
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    @Test
    fun `finalization refuses both current and previous targets without changing bytes`() = runTest {
        val current = installed("current")
        val previous = installed("previous")
        packStateStore.writeCurrent(current)
        packStateStore.writePrevious(previous)
        val stateBytes = listOf("current.json", "previous.json").associateWith { File(baseDir, it).readText() }
        listOf(current, previous).forEach { pack ->
            val dir = packStateStore.packDir(pack.version).apply { mkdirs() }
            val sentinel = File(dir, "tiles.mbtiles").apply { writeText("kept-${pack.version}") }
            val before = sentinel.readText()
            val outcome = installer.finalize(dir, pack.version, manifestWithTilesHash("bad"), "{}", 0)
            assertThat(outcome).isEqualTo(PackInstaller.Outcome.VerificationFailed("pack_target_referenced"))
            assertThat(sentinel.readText()).isEqualTo(before)
        }
        stateBytes.forEach { (name, bytes) -> assertThat(File(baseDir, name).readText()).isEqualTo(bytes) }
    }

    @Test
    fun `supplied but undecoded manifest is not a supported legacy install`() = runTest {
        val dir = packStateStore.packDir("new").apply { mkdirs() }
        File(dir, "tiles.mbtiles").writeText("unverified")
        val outcome = installer.finalize(dir, "new", null, "private-corrupt-payload", 1)
        assertThat(outcome).isEqualTo(PackInstaller.Outcome.VerificationFailed("pack_manifest_invalid"))
        assertThat(dir.exists()).isFalse()
        assertThat(packStateStore.readCurrent()).isNull()
        assertThat(baseDir.exists()).isFalse()
    }

    @Test
    fun `failed install preserves previous-only metadata even when its directory is missing`() = runTest {
        packStateStore.writePrevious(installed("keep"))
        val previousBytes = File(baseDir, "previous.json").readText()
        val dir = packStateStore.packDir("new").apply { mkdirs() }
        File(dir, "tiles.mbtiles").writeText("bad")
        val outcome = installer.finalize(dir, "new", manifestWithTilesHash("bad-hash"), "{}", 0)
        assertThat(outcome).isInstanceOf(PackInstaller.Outcome.VerificationFailed::class.java)
        assertThat(dir.exists()).isFalse()
        assertThat(File(baseDir, "previous.json").readText()).isEqualTo(previousBytes)
    }

    @Test
    fun `new install with no current keeps still-referenced previous bytes`() = runTest {
        val keepDir = packStateStore.packDir("keep").apply { mkdirs() }
        val sentinel = File(keepDir, "tiles.mbtiles").apply { writeText("keep-previous") }
        packStateStore.writePrevious(installed("keep"))
        val previousBytes = File(baseDir, "previous.json").readText()
        val dir = packStateStore.packDir("new").apply { mkdirs() }
        File(dir, "tiles.mbtiles").writeText("new")
        assertThat(installer.finalize(dir, "new", null, null, 1)).isEqualTo(PackInstaller.Outcome.Installed)
        assertThat(sentinel.readText()).isEqualTo("keep-previous")
        assertThat(File(baseDir, "previous.json").readText()).isEqualTo(previousBytes)
        assertThat(packStateStore.readCurrent()?.version).isEqualTo("new")
    }

    @Test
    fun `failed attempt traversal preserves complete adoption and scratch tree`() = runTest {
        seedPreservedState()
        File(baseDir, "vother/nested/sentinel").apply { parentFile!!.mkdirs(); writeText("nested bytes") }
        val before = snapshot()
        // Real cleanup, not an isolated canonical-path assertion. The old target
        // vcurrent/../current.json resolves to and deletes adopted metadata.
        (listOf("current", "previous") + invalidVersions).forEach { version ->
            installer.cleanupFailedAttempt(version)
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    @Test
    fun `invalid versions refuse extraction finalization and cleanup before any mutation`() = runTest {
        seedPreservedState()
        File(baseDir, "vother/nested/sentinel").apply { parentFile!!.mkdirs(); writeText("nested bytes") }
        invalidVersions.forEach { assertAttemptRefused(it, "pack_target_invalid") }
    }

    @Test
    fun `current and previous targets refuse all entrypoints with complete byte preservation`() = runTest {
        seedPreservedState()
        listOf("current", "previous").forEach { assertAttemptRefused(it, "pack_target_referenced") }
    }

    @Test
    fun `finalization refuses metadata scratch nested mismatched and non-directory targets`() = runTest {
        seedPreservedState()
        val regularFile = File(baseDir, "vnot-a-directory").apply { writeText("not an install directory") }
        val before = snapshot()
        val targets = listOf(
            File(baseDir, "current.json") to "new", File(baseDir, "previous.json") to "new",
            File(baseDir, "manifest-cache.json") to "new", packStateStore.partialDir() to "new",
            baseDir to "new", File(baseDir, "vcurrent/nested") to "current",
            File(baseDir, "vwrong") to "new", File(baseDir, "vwrong/../vnew") to "new",
            File(tempDir, "vnew") to "new", regularFile to "not-a-directory", File(baseDir, "vnew") to "new",
        )
        targets.forEach { (target, version) ->
            assertFailure(installer.finalize(target, version, null, null, 0), "pack_target_invalid")
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    @Test
    fun `invalid target without metadata cannot trigger empty-base or scratch cleanup`() = runTest {
        zipInScratch()
        File(packStateStore.partialDir(), "resume.marker").writeBytes(byteArrayOf(0, 1, -1, 42))
        invalidVersions.forEach { assertAttemptRefused(it, "pack_target_invalid") }
    }

    @Test
    fun `malformed metadata and unsafe reference versions refuse without deleting bytes`() = runTest {
        listOf("current.json", "previous.json").forEach { name ->
            val malformed = listOf("{private-corrupt-state") + invalidVersions.map { version ->
                Json.encodeToString(InstalledPack.serializer(), installed(version))
            }
            malformed.forEach { raw ->
                seedPreservedState()
                File(baseDir, name).writeText(raw)
                File(baseDir, "vnew/sentinel").apply { parentFile!!.mkdirs(); writeText("unknown reference") }
                assertAttemptRefused("new", "pack_state_unreadable")
            }
        }
    }

    @Test
    fun `symlink target cannot alias another version or escape the region`() = runTest {
        seedPreservedState()
        val outside = File(tempDir, "outside").apply { mkdirs() }
        File(outside, "sentinel").writeText("outside bytes")
        val target = File(baseDir, "vnew")
        listOf(File(baseDir, "vcurrent"), outside, File(tempDir, "missing")).forEach { destination ->
            Files.createSymbolicLink(target.toPath(), destination.toPath())
            assertAttemptRefused("new", "pack_target_invalid")
            assertThat(target.delete()).isTrue() // Fixture-only link removal, never the destination.
        }
        target.mkdirs()
        Files.createSymbolicLink(File(target, "nested").toPath(), File(baseDir, "vcurrent").toPath())
        assertAttemptRefused("new", "pack_target_invalid")
    }

    @Test
    fun `symlink metadata and reference directory are unreadable not deletion authority`() = runTest {
        seedPreservedState()
        val metadata = File(baseDir, "current.json")
        val outside = File(tempDir, "external-metadata.json").apply { writeBytes(metadata.readBytes()) }
        assertThat(metadata.delete()).isTrue()
        Files.createSymbolicLink(metadata.toPath(), outside.toPath())
        assertAttemptRefused("new", "pack_state_unreadable")
        assertThat(metadata.delete()).isTrue()
        Files.createSymbolicLink(metadata.toPath(), File(tempDir, "missing-metadata").toPath())
        assertAttemptRefused("new", "pack_state_unreadable")
        assertThat(metadata.delete()).isTrue()
        packStateStore.writeCurrent(installed("current"))
        val previous = File(baseDir, "vprevious")
        val retained = File(tempDir, "retained-previous")
        assertThat(previous.renameTo(retained)).isTrue()
        Files.createSymbolicLink(previous.toPath(), retained.toPath())
        assertAttemptRefused("new", "pack_state_unreadable")
    }

    @Test
    fun `healthy dated dotted and semver installs still extract and retain old current bytes`() = runTest {
        seedPreservedState()
        listOf("2026-10-13", "2026.10.13", "1", "release_1.2.3-rc1+local").forEach { version ->
            val oldCurrent = packStateStore.readCurrent()!!
            val oldBytes = snapshot(packStateStore.packDir(oldCurrent.version))
            val scratchBytes = snapshot(packStateStore.partialDir())
            val phases = mutableListOf<Phase>()
            val outcome = installer.extractAndFinalize(
                File(packStateStore.partialDir(), "pack.zip"), version, null, PackExtractor(),
            ) { phases.add(it) }
            assertThat(outcome).isEqualTo(PackInstaller.Outcome.Installed)
            assertThat(phases).containsExactly(Phase.EXTRACTING, Phase.VERIFYING, Phase.INSTALLING).inOrder()
            assertThat(packStateStore.readCurrent()?.version).isEqualTo(version)
            assertThat(packStateStore.readPrevious()).isEqualTo(oldCurrent)
            assertThat(snapshot(packStateStore.packDir(oldCurrent.version))).isEqualTo(oldBytes)
            assertThat(snapshot(packStateStore.partialDir())).isEqualTo(scratchBytes)
            payloadPaths.forEach { path ->
                assertThat(File(packStateStore.packDir(version), path).readText()).isEqualTo("replacement-$path")
            }
        }
    }

    private val invalidVersions = listOf(
        "current/../current.json", "current/../previous.json", "current/../manifest-cache.json",
        "current/../.partial", "current/..", "other/nested", "new/../vnew",
        "", " ", ".", "..", "new\\nested", "new\u0000suffix", "new\nsuffix",
    )
    private val payloadPaths = listOf("tiles.mbtiles", "search.db", "routing/valhalla_tiles.tar")

    private suspend fun assertAttemptRefused(version: String, message: String) {
        val before = snapshot()
        val phases = mutableListOf<Phase>()
        val outcome = installer.extractAndFinalize(
            File(packStateStore.partialDir(), "pack.zip"), version, null, PackExtractor(),
        ) { phases.add(it) }
        assertFailure(outcome, message)
        assertThat(phases).isEmpty()
        assertThat(snapshot()).isEqualTo(before)
        // Raw old-layout File is deliberate: a hardened packDir getter must not
        // prevent this control from testing the finalize boundary itself.
        assertFailure(installer.finalize(File(baseDir, "v$version"), version, null, null, 0), message)
        assertThat(snapshot()).isEqualTo(before)
        installer.cleanupFailedAttempt(version)
        assertThat(snapshot()).isEqualTo(before)
    }

    private fun assertFailure(outcome: PackInstaller.Outcome, message: String) {
        assertThat(outcome).isEqualTo(PackInstaller.Outcome.VerificationFailed(message))
        assertThat(workerFailureData((outcome as PackInstaller.Outcome.VerificationFailed).message)
            .getString(MapPackDownloadWorker.KEY_RESULT_ERROR)).isEqualTo(message)
    }

    private suspend fun seedPreservedState() {
        listOf("current", "previous").forEach { version ->
            payloadPaths.forEach { path ->
                File(baseDir, "v$version/$path").apply { parentFile!!.mkdirs(); writeText("sentinel-$version-$path") }
            }
        }
        packStateStore.writeCurrent(installed("current").copy(searchPath = "search.db", routingPath = payloadPaths.last()))
        packStateStore.writePrevious(installed("previous").copy(searchPath = "search.db", routingPath = payloadPaths.last()))
        File(baseDir, "manifest-cache.json").writeText("cache sentinel")
        zipInScratch()
        File(packStateStore.partialDir(), "resume.marker").writeBytes(byteArrayOf(0, 1, -1, 42))
    }

    private fun zipInScratch(): File = File(packStateStore.partialDir(), "pack.zip").apply {
        parentFile!!.mkdirs()
        ZipOutputStream(outputStream()).use { output ->
            payloadPaths.forEach { path ->
                output.putNextEntry(ZipEntry(path))
                output.write("replacement-$path".toByteArray())
                output.closeEntry()
            }
        }
    }

    // Includes metadata, payloads, scratch, directories and dangling links;
    // unlike the worker-tail snapshot, there is NO .partial exclusion or link following.
    private fun snapshot(root: File = tempDir): Map<String, Pair<String, List<Byte>>> =
        Files.walk(root.toPath()).use { paths ->
            paths.iterator().asSequence().associate { path ->
                val entry = when {
                    Files.isSymbolicLink(path) -> "link" to Files.readSymbolicLink(path).toString().toByteArray().toList()
                    Files.isDirectory(path) -> "directory" to emptyList<Byte>()
                    else -> "file" to Files.readAllBytes(path).toList()
                }
                root.toPath().relativize(path).toString() to entry
            }
        }

    private fun installed(version: String) = InstalledPack(
        version = version,
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-01-01T00:00:00Z"),
        totalSizeBytes = 1,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "sentinel",
    )

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
