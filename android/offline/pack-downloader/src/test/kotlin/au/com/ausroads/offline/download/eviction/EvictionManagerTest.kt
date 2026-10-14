package au.com.ausroads.offline.download.eviction

import android.content.Context
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Comparator

class EvictionManagerTest {

    private lateinit var tempDir: File
    private lateinit var packStateStore: PackStateStore
    private lateinit var evictionManager: EvictionManager
    private lateinit var realStore: PackStateStore
    private lateinit var realEviction: EvictionManager
    private lateinit var baseDir: File

    private val packV1 = InstalledPack(
        version = "1",
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-01-01T00:00:00Z"),
        totalSizeBytes = 100_000_000,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "abc",
    )

    private val packV2 = InstalledPack(
        version = "2",
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-02-01T00:00:00Z"),
        totalSizeBytes = 120_000_000,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "def",
    )

    private val packV3 = packV2.copy(version = "3")
    private val payloadPaths = listOf("tiles.mbtiles", "search.db", "routing/valhalla_tiles.tar")
    private val invalidVersions = listOf(
        "", " ", ".", "..", "current/../current.json", "current/../previous.json",
        "current/../manifest-cache.json", "current/../.partial", "current/..", "other/nested",
        "new/../vnew", "new\\nested", "new\u0000suffix", "new\nsuffix",
    )
    private val targetInvalid = "component=map-pack-state seam=eviction-preflight class=target-invalid"
    private val stateUnreadable = "component=map-pack-state seam=reference-read class=metadata-unreadable"

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("eviction-test").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        realStore = PackStateStore(context)
        realEviction = EvictionManager(realStore)
        baseDir = File(tempDir, "mappacks/au-sa")
        packStateStore = mockk(relaxed = true)
        every { packStateStore.packDir(any()) } answers { realStore.packDir(firstArg<String>()) }
        every { packStateStore.installTarget(any()) } answers { realStore.installTarget(firstArg<String>()) }
        evictionManager = EvictionManager(packStateStore)
    }

    @After
    fun tearDown() {
        // A failed assertion must not make fixture cleanup traverse a surviving link.
        deleteFixtureTree(tempDir)
    }

    @Test
    fun `reconcile keeps current and previous versions`() = runTest {
        val currentDir = File(baseDir, "v1")
        val previousDir = File(baseDir, "v2")
        val staleDir = File(baseDir, "v0")
        currentDir.mkdirs()
        previousDir.mkdirs()
        staleDir.mkdirs()

        coEvery { packStateStore.readCurrent() } returns packV1
        coEvery { packStateStore.readPrevious() } returns packV2

        evictionManager.reconcile()

        assertThat(currentDir.exists()).isTrue()
        assertThat(previousDir.exists()).isTrue()
        assertThat(staleDir.exists()).isFalse()
    }

    @Test
    fun `reconcile reverts to previous when current dir is missing`() = runTest {
        val previousDir = File(baseDir, "v2")
        previousDir.mkdirs()

        coEvery { packStateStore.readCurrent() } returns packV1
        coEvery { packStateStore.readPrevious() } returns packV2

        evictionManager.reconcile()

        coVerify { packStateStore.writeCurrent(packV2) }
        coVerify { packStateStore.writePrevious(null) }
    }

    @Test
    fun `reconcile clears state when both current and previous dirs are missing`() = runTest {
        coEvery { packStateStore.readCurrent() } returns packV1
        coEvery { packStateStore.readPrevious() } returns packV2

        evictionManager.reconcile()

        coVerify { packStateStore.clearCurrent() }
        coVerify { packStateStore.writePrevious(null) }
        coVerify(exactly = 0) { packStateStore.writeCurrent(any()) }
    }

    @Test
    fun `onNewInstall promotes current to previous`() = runTest {
        coEvery { packStateStore.readCurrent() } returns packV1

        evictionManager.onNewInstall(packV2)

        coVerify { packStateStore.writePrevious(packV1) }
        coVerify { packStateStore.writeCurrent(packV2) }
    }

    @Test
    fun `onNewInstall deletes stale version dirs`() = runTest {
        val staleDir = File(baseDir, "v0")
        staleDir.mkdirs()
        coEvery { packStateStore.readCurrent() } returns packV1

        evictionManager.onNewInstall(packV2)

        assertThat(staleDir.exists()).isFalse()
    }

    @Test
    fun `invalid new versions refuse publication and preserve complete trees repeatedly`() = runTest {
        seedPreservedState()
        invalidVersions.forEach { assertNewInstallRefused(it) }
        // Positive control: the same state still permits an ordinary installation.
        realEviction.onNewInstall(packV3)
        assertThat(realStore.readCurrent()).isEqualTo(packV3)
        assertThat(realStore.readPrevious()).isEqualTo(packV1)
    }

    @Test
    fun `invalid new versions without metadata cannot clean scratch or create placeholder state`() = runTest {
        writeSentinel(File(realStore.partialDir(), "pack.zip"), "resume archive")
        writeSentinel(File(tempDir, "outside/sentinel"), "outside bytes")
        invalidVersions.forEach { assertNewInstallRefused(it) }
        assertThat(Files.notExists(File(baseDir, "current.json").toPath(), NOFOLLOW_LINKS)).isTrue()
        assertThat(Files.notExists(File(baseDir, "previous.json").toPath(), NOFOLLOW_LINKS)).isTrue()
    }

    @Test
    fun `new target file root and nested links refuse before metadata rotation`() = runTest {
        seedPreservedState()
        val target = realStore.packDir("3")
        deleteFixtureTree(target)
        writeSentinel(target, "not an install directory")
        assertNewInstallRefused("3")
        Files.delete(target.toPath())
        linkDestinations().forEach { destination ->
            Files.createSymbolicLink(target.toPath(), destination.toPath())
            assertNewInstallRefused("3")
            Files.delete(target.toPath()) // Only the fixture entry, never its destination.
        }
        seedPayload("3")
        val nested = File(target, "routing/redirect")
        linkDestinations().forEach { destination ->
            Files.createSymbolicLink(nested.toPath(), destination.toPath())
            assertNewInstallRefused("3")
            Files.delete(nested.toPath())
        }
        val before = snapshot()
        realEviction.onNewInstall(packV3)
        val expected = withoutVersions(before, "0", "2")
        expected["mappacks/au-sa/previous.json"] = before.getValue("mappacks/au-sa/current.json")
        expected["mappacks/au-sa/current.json"] = metadataEntry(packV3)
        assertThat(snapshot()).isEqualTo(expected)
    }

    @Test
    fun `stale root and nested links refuse both entrypoints without deleting any earlier candidate`() = runTest {
        seedPreservedState() // Includes an ordinary v0 stale tree before the poisoned candidate.
        val stale = File(baseDir, "vlinked")
        linkDestinations().forEach { destination ->
            Files.createSymbolicLink(stale.toPath(), destination.toPath())
            assertBothRefused(targetInvalid)
            Files.delete(stale.toPath())
        }
        seedPayload("linked")
        val nested = File(stale, "routing/redirect")
        linkDestinations().forEach { destination ->
            Files.createSymbolicLink(nested.toPath(), destination.toPath())
            assertBothRefused(targetInvalid)
            Files.delete(nested.toPath())
        }
        val before = snapshot()
        realEviction.reconcile()
        assertThat(snapshot()).isEqualTo(withoutVersions(before, "0", "3", "linked"))
    }

    @Test
    fun `stale directory names use the Store identity rule rather than raw v prefix deletion`() = runTest {
        seedPreservedState()
        listOf("v", "v.", "v..", "v   ", "vbad\\component").forEach { name ->
            val stale = File(baseDir, name)
            writeSentinel(File(stale, "nested/sentinel"), "invalid identity bytes")
            assertBothRefused(targetInvalid)
            deleteFixtureTree(stale)
        }
    }

    @Test
    fun `malformed and empty version metadata refuse both entrypoints without mutation`() = runTest {
        seedPreservedState()
        listOf("current.json", "previous.json").forEach { name ->
            val metadata = File(baseDir, name)
            val original = metadata.readBytes()
            listOf("{private-corrupt-metadata", "null", Json.encodeToString(InstalledPack.serializer(), packV1.copy(version = "")))
                .forEach { raw ->
                    metadata.writeText(raw)
                    assertBothRefused(stateUnreadable)
                }
            metadata.writeBytes(original)
        }
    }

    @Test
    fun `existing and dangling metadata links are unreadable not absence or publication authority`() = runTest {
        seedPreservedState()
        listOf("current.json", "previous.json").forEach { name ->
            val metadata = File(baseDir, name)
            val original = metadata.readBytes()
            val outside = File(tempDir, "outside/$name").apply { writeBytes(original) }
            listOf(outside, File(tempDir, "outside/missing-$name")).forEach { destination ->
                Files.delete(metadata.toPath())
                Files.createSymbolicLink(metadata.toPath(), destination.toPath())
                assertBothRefused(stateUnreadable)
                Files.delete(metadata.toPath())
                metadata.writeBytes(original)
            }
        }
    }

    @Test
    fun `non-file metadata refuses instead of authorizing cleanup of unknown bytes`() = runTest {
        seedPreservedState()
        listOf("current.json", "previous.json").forEach { name ->
            val metadata = File(baseDir, name)
            val original = metadata.readBytes()
            Files.delete(metadata.toPath())
            writeSentinel(File(metadata, "sentinel"), "unreadable directory metadata")
            assertBothRefused(stateUnreadable)
            deleteFixtureTree(metadata)
            metadata.writeBytes(original)
        }
    }

    @Test
    fun `linked current and previous trees refuse as unreadable references before deletion`() = runTest {
        seedPreservedState()
        listOf("1", "2").forEach { version ->
            val target = realStore.packDir(version)
            val retained = File(tempDir, "outside/retained-$version")
            Files.move(target.toPath(), retained.toPath())
            Files.createSymbolicLink(target.toPath(), retained.toPath())
            assertBothRefused(stateUnreadable)
            Files.delete(target.toPath())
            Files.move(retained.toPath(), target.toPath())
            val nested = File(target, "routing/redirect")
            Files.createSymbolicLink(nested.toPath(), File(tempDir, "outside").toPath())
            assertBothRefused(stateUnreadable)
            Files.delete(nested.toPath())
        }
    }

    @Test
    fun `stale link preflight precedes fallback and real clearing of missing references`() = runTest {
        listOf(true, false).forEach { previousExists ->
            seedPreservedState()
            deleteFixtureTree(realStore.packDir("1"))
            if (!previousExists) deleteFixtureTree(realStore.packDir("2"))
            val stale = File(baseDir, "vlinked")
            Files.createSymbolicLink(stale.toPath(), File(tempDir, "outside").toPath())
            assertBothRefused(targetInvalid)
            Files.delete(stale.toPath())
            realEviction.reconcile()
            assertThat(realStore.readCurrent()).isEqualTo(if (previousExists) packV2 else null)
            assertThat(realStore.readPrevious()).isNull()
        }
    }

    @Test
    fun `real reconcile retains full current previous metadata payload scratch and outside bytes`() = runTest {
        seedPreservedState()
        val before = snapshot()
        realEviction.reconcile()
        val expected = withoutVersions(before, "0", "3")
        assertThat(snapshot()).isEqualTo(expected)
        realEviction.reconcile()
        assertThat(snapshot()).isEqualTo(expected)
    }

    @Test
    fun `real new install rotates current and deletes only old previous and stale trees`() = runTest {
        seedPreservedState()
        val before = snapshot()
        realEviction.onNewInstall(packV3)
        val expected = withoutVersions(before, "0", "2")
        expected["mappacks/au-sa/previous.json"] = before.getValue("mappacks/au-sa/current.json")
        expected["mappacks/au-sa/current.json"] = metadataEntry(packV3)
        assertThat(realStore.readCurrent()).isEqualTo(packV3)
        assertThat(realStore.readPrevious()).isEqualTo(packV1)
        assertThat(snapshot()).isEqualTo(expected)
        realEviction.reconcile()
        assertThat(snapshot()).isEqualTo(expected)
    }

    @Test
    fun `real new install with absent current preserves previous record and payload`() = runTest {
        seedPreservedState()
        realStore.clearCurrent()
        val before = snapshot()
        realEviction.onNewInstall(packV3)
        val expected = withoutVersions(before, "0", "1")
        expected["mappacks/au-sa/current.json"] = metadataEntry(packV3)
        assertThat(realStore.readCurrent()).isEqualTo(packV3)
        assertThat(realStore.readPrevious()).isEqualTo(packV2)
        assertThat(snapshot()).isEqualTo(expected)
    }

    @Test
    fun `real fallback publishes previous and preserves all unrelated bytes`() = runTest {
        seedPreservedState()
        deleteFixtureTree(realStore.packDir("1"))
        val before = snapshot()
        realEviction.reconcile()
        val expected = withoutVersions(before, "0", "3")
        expected["mappacks/au-sa/current.json"] = before.getValue("mappacks/au-sa/previous.json")
        expected.remove("mappacks/au-sa/previous.json")
        assertThat(realStore.readCurrent()).isEqualTo(packV2)
        assertThat(realStore.readPrevious()).isNull()
        assertThat(snapshot()).isEqualTo(expected)
        realEviction.reconcile()
        assertThat(snapshot()).isEqualTo(expected)
    }

    @Test
    fun `real missing current previous directories clear both records without an empty version placeholder`() = runTest {
        seedPreservedState()
        deleteFixtureTree(realStore.packDir("1"))
        deleteFixtureTree(realStore.packDir("2"))
        val before = snapshot()
        realEviction.reconcile()
        val expected = withoutVersions(before, "0", "3")
        expected.remove("mappacks/au-sa/current.json")
        expected.remove("mappacks/au-sa/previous.json")
        assertThat(realStore.readCurrent()).isNull()
        assertThat(realStore.readPrevious()).isNull()
        assertThat(snapshot()).isEqualTo(expected)
        EvictionManager(realStore).reconcile() // A fresh manager must see actual absence.
        assertThat(snapshot()).isEqualTo(expected)
    }

    @Test
    fun `real absent metadata permits stale cleanup but preserves cache scratch unknown and outside bytes`() = runTest {
        seedPreservedState()
        realStore.clearCurrent()
        realStore.writePrevious(null)
        val before = snapshot()
        realEviction.reconcile()
        val expected = withoutVersions(before, "0", "1", "2", "3")
        assertThat(snapshot()).isEqualTo(expected)
        realEviction.reconcile()
        assertThat(snapshot()).isEqualTo(expected)
    }

    private suspend fun assertNewInstallRefused(version: String) {
        val before = snapshot()
        repeat(2) {
            assertBoundaryFailure(runCatching { realEviction.onNewInstall(packV3.copy(version = version)) }.exceptionOrNull(), targetInvalid)
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    private suspend fun assertBothRefused(message: String) {
        val before = snapshot()
        repeat(2) {
            assertBoundaryFailure(runCatching { realEviction.reconcile() }.exceptionOrNull(), message)
            assertThat(snapshot()).isEqualTo(before)
            assertBoundaryFailure(runCatching { realEviction.onNewInstall(packV3) }.exceptionOrNull(), message)
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    private fun assertBoundaryFailure(failure: Throwable?, message: String) {
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).isEqualTo(message) // No supplied version/path/metadata payload.
    }

    private suspend fun seedPreservedState() {
        listOf("0", "1", "2", "3").forEach(::seedPayload)
        File(baseDir, "v0/nested/empty").mkdirs()
        realStore.writeCurrent(packV1)
        realStore.writePrevious(packV2)
        listOf("manifest-cache.json", "current.json.tmp.abandoned", "previous.json.tmp.abandoned",
            ".partial/pack.zip", ".partial/resume.marker", ".partial/nested/state", "unknown/nested/sentinel")
            .forEach { path -> writeSentinel(File(baseDir, path), "keep-$path") }
        File(baseDir, "unknown/empty").mkdirs()
        writeSentinel(File(tempDir, "outside/deep/sentinel"), "outside payload")
        writeSentinel(File(tempDir, "outside/metadata.json"), "outside metadata")
        writeSentinel(File(tempDir, "unrelated-user-state"), "user bytes")
    }

    private fun seedPayload(version: String) {
        val target = realStore.packDir(version)
        payloadPaths.forEach { path -> writeSentinel(File(target, path), "payload-$version-$path") }
    }

    private fun writeSentinel(file: File, label: String) {
        file.parentFile!!.mkdirs()
        file.writeBytes(label.toByteArray() + byteArrayOf(0, 1, -1, 42))
    }

    private fun linkDestinations() = listOf(
        File(baseDir, "v1"), File(baseDir, "v2"), File(tempDir, "outside"), File(tempDir, "missing-destination"),
    )

    private fun metadataEntry(pack: InstalledPack) =
        "file" to Json.encodeToString(InstalledPack.serializer(), pack).toByteArray().toList()

    private fun withoutVersions(before: Map<String, Pair<String, List<Byte>>>, vararg versions: String) =
        before.filterKeys { path ->
            versions.none { path == "mappacks/au-sa/v$it" || path.startsWith("mappacks/au-sa/v$it/") }
        }.toMutableMap()

    // Includes all metadata, payload, scratch, outside/unknown files, empty directories
    // and link identities. Default Files.walk never follows fixture link destinations.
    private fun snapshot(): Map<String, Pair<String, List<Byte>>> = Files.walk(tempDir.toPath()).use { paths ->
        paths.iterator().asSequence().associate { path ->
            val entry = when {
                Files.isSymbolicLink(path) -> "link" to Files.readSymbolicLink(path).toString().toByteArray().toList()
                Files.isDirectory(path, NOFOLLOW_LINKS) -> "directory" to emptyList<Byte>()
                else -> "file" to Files.readAllBytes(path).toList()
            }
            tempDir.toPath().relativize(path).toString() to entry
        }
    }

    private fun deleteFixtureTree(root: File) {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return
        Files.walk(root.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
}
