package au.com.ausroads.offline.download.state

import android.content.Context
import au.com.ausroads.offline.download.eviction.EvictionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.After
import org.junit.Assume
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator

class PackStateStoreTest {

    private lateinit var tempDir: File
    private lateinit var store: PackStateStore
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("pack-state-test")
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        store = PackStateStore(context)
        baseDir = File(tempDir, "mappacks/au-sa")
    }

    @After
    fun tearDown() {
        Files.walk(tempDir.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    @Test
    fun `cleanupIfEmpty removes empty base dir`() = runTest {
        baseDir.mkdirs()
        store.partialDir().mkdirs() // a leftover scratch dir must not block cleanup

        store.cleanupIfEmpty()

        assertThat(baseDir.exists()).isFalse()
    }

    @Test
    fun `cleanupIfEmpty keeps base dir when current json present`() = runTest {
        baseDir.mkdirs()
        File(baseDir, "current.json").writeText("{}")

        store.cleanupIfEmpty()

        assertThat(baseDir.exists()).isTrue()
    }

    @Test
    fun `cleanupIfEmpty keeps base dir when a version dir present`() = runTest {
        File(baseDir, "v1").mkdirs()

        store.cleanupIfEmpty()

        assertThat(baseDir.exists()).isTrue()
        assertThat(File(baseDir, "v1").exists()).isTrue()
    }

    @Test
    fun `InstalledPack serialization round-trip`() {
        val pack = InstalledPack(
            version = "2026.06.01",
            regionCode = "AU-SA",
            installedAt = Instant.fromEpochMilliseconds(1717200000000),
            totalSizeBytes = 150_000_000,
            tilesPath = "tiles.mbtiles",
            searchPath = "search.db",
            routingPath = null,
            manifestSha256 = "abc123",
        )

        val json = Json.encodeToString(InstalledPack.serializer(), pack)
        val decoded = Json.decodeFromString(InstalledPack.serializer(), json)

        assertThat(decoded.version).isEqualTo("2026.06.01")
        assertThat(decoded.regionCode).isEqualTo("AU-SA")
        assertThat(decoded.totalSizeBytes).isEqualTo(150_000_000)
        assertThat(decoded.searchPath).isEqualTo("search.db")
        assertThat(decoded.routingPath).isNull()
    }

    @Test
    fun `DownloadProgress percent calculation`() {
        val progress = DownloadProgress(
            phase = DownloadProgress.Phase.DOWNLOADING,
            bytesDownloaded = 50_000_000,
            totalBytes = 150_000_000,
        )
        assertThat(progress.percent).isEqualTo(33)
    }

    @Test
    fun `DownloadProgress percent is null when totalBytes is null`() {
        val progress = DownloadProgress(
            phase = DownloadProgress.Phase.DOWNLOADING,
            bytesDownloaded = 50_000_000,
            totalBytes = null,
        )
        assertThat(progress.percent).isNull()
    }

    @Test
    fun `clearCurrent removes the state so an uninstalled pack cannot resurrect`() = runTest {
        val pack = InstalledPack(
            version = "2026-08-22",
            regionCode = "AU-SA",
            installedAt = Instant.parse("2026-08-22T00:00:00Z"),
            totalSizeBytes = 1L,
            tilesPath = "tiles.pmtiles",
            searchPath = null,
            routingPath = null,
            manifestSha256 = "a".repeat(64),
        )
        store.writeCurrent(pack)
        assertThat(store.readCurrent()).isNotNull()

        store.clearCurrent()

        assertThat(store.readCurrent()).isNull()
    }

    @Test
    fun `atomic write replaces content and leaves no temp file behind`() = runTest {
        store.writeCurrent(samplePack("1"))
        assertThat(store.readCurrent()?.version).isEqualTo("1")

        store.writeCurrent(samplePack("2"))

        assertThat(store.readCurrent()?.version).isEqualTo("2")
        assertThat(baseDir.listFiles()?.none { ".tmp" in it.name }).isTrue()
    }

    @Test
    fun `interrupted write keeps the previous valid JSON and removes its temp file`() = runTest {
        store.writeCurrent(samplePack("1"))
        // Interrupt-simulation WITHOUT touching java.lang.System (MockK static
        // instrumentation of System poisons the whole test JVM): seal baseDir
        // read-only so the UNIQUE temp file cannot be created — the failure
        // happens before any rename, so the previous valid JSON must survive
        // and the failed writer leaves nothing behind. Skipped where the OS
        // cannot enforce the seal (root's DAC override, Windows dir bits):
        // assuming rather than false-failing keeps CI environments honest.
        val sealed = baseDir.setWritable(false)
        val probe = File(baseDir, ".seal-probe")
        // createNewFile succeeding proves the seal is unenforceable here -> skip.
        Assume.assumeTrue(sealed && runCatching { !probe.createNewFile() }.getOrDefault(true))
        try {
            var failed = false
            try {
                store.writeCurrent(samplePack("2"))
            } catch (_: Exception) {
                failed = true
            }

            assertThat(failed).isTrue()
            assertThat(store.readCurrent()?.version).isEqualTo("1")
            assertThat(baseDir.listFiles()?.none { ".tmp" in it.name }).isTrue()
        } finally {
            baseDir.setWritable(true)
            probe.delete()
        }
    }

    @Test
    fun `interrupted write leaves absent JSON when no prior state existed`() = runTest {
        // Same interrupt-simulation as above (read-only baseDir blocks temp
        // creation before the rename), without prior state: the attempt must
        // fail and leave nothing behind. Sealed-capability assumption as above.
        baseDir.mkdirs()
        val sealed = baseDir.setWritable(false)
        val probe = File(baseDir, ".seal-probe")
        Assume.assumeTrue(sealed && runCatching { !probe.createNewFile() }.getOrDefault(true))
        try {
            var failed = false
            try {
                store.writeCurrent(samplePack("2"))
            } catch (_: Exception) {
                failed = true
            }

            assertThat(failed).isTrue()
            assertThat(store.readCurrent()).isNull()
            assertThat(baseDir.listFiles()?.none { ".tmp" in it.name }).isTrue()
        } finally {
            baseDir.setWritable(true)
            probe.delete()
        }
    }

    @Test
    fun `packDir keeps empty base lookup and valid dated dotted semver layouts`() {
        assertThat(store.packDir("")).isEqualTo(File(baseDir, "v"))
        assertThat(store.packDir("").parentFile).isEqualTo(baseDir)
        listOf("1", "current", "previous", "2026-10-13", "2026.10.13", "release_1.2.3-rc1+local").forEach { version ->
            assertThat(store.packDir(version)).isEqualTo(File(baseDir, "v$version"))
        }
        assertThat(baseDir.exists()).isFalse()
    }

    @Test
    fun `packDir rejects unsafe nonempty components with fixed payload and no mutation`() {
        listOf(" ", ".", "..", "current/../current.json", "current/../.partial", "other/nested",
            "new\\nested", "new\u0000suffix", "new\nsuffix").forEach { version ->
            val failure = runCatching { store.packDir(version) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure?.message).isEqualTo("pack_target_invalid")
            assertThat(baseDir.exists()).isFalse()
        }
    }

    @Test
    fun `cleanupIfEmpty preserves previous cache unknown and nonempty resume bytes`() = runTest {
        listOf("previous.json", "manifest-cache.json", "current.json.tmp.old", "unknown/sentinel", ".partial/resume.marker")
            .forEach { path ->
                val sentinel = File(baseDir, path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0, 1, -1, 42)) }
                val before = sentinel.readBytes().toList()
                store.cleanupIfEmpty()
                assertThat(baseDir.exists()).isTrue()
                assertThat(sentinel.readBytes().toList()).isEqualTo(before)
                baseDir.deleteRecursively() // Only reset this temporary fixture between independent controls.
            }
    }

    @Test
    fun `unsafe persisted reference versions block reconciliation and direct adoption before writes`() = runTest {
        listOf("current.json", "previous.json").forEach { name ->
            listOf("", ".", "..", "current/../current.json").forEach { version ->
                store.writeCurrent(samplePack("1"))
                store.writePrevious(samplePack("2"))
                val paths = listOf("current.json", "previous.json", "v1/tiles.mbtiles", "v2/tiles.mbtiles", "vunknown/sentinel")
                paths.drop(2).forEach { path -> File(baseDir, path).apply { parentFile!!.mkdirs(); writeText("keep-$path") } }
                File(baseDir, name).writeText(Json.encodeToString(InstalledPack.serializer(), samplePack(version)))
                val before = paths.associateWith { File(baseDir, it).readBytes().toList() }
                val eviction = EvictionManager(store)
                val failures = listOf(
                    runCatching { eviction.reconcile() }.exceptionOrNull(),
                    runCatching { eviction.onNewInstall(samplePack("new")) }.exceptionOrNull(),
                )
                failures.forEach { failure ->
                    assertThat(failure).isInstanceOf(IllegalStateException::class.java)
                    assertThat(failure?.message).isEqualTo("component=map-pack-state seam=reference-read class=metadata-unreadable")
                }
                before.forEach { (path, bytes) -> assertThat(File(baseDir, path).readBytes().toList()).isEqualTo(bytes) }
            }
        }
    }

    @Test
    fun `cleanup and base lookups refuse substituted region without deleting external bytes`() = runTest {
        val outside = File(tempDir, "outside").apply { mkdirs() }
        val sentinel = File(outside, "vkeep/tiles.mbtiles").apply { parentFile!!.mkdirs(); writeText("outside bytes") }
        baseDir.parentFile!!.mkdirs()
        Files.createSymbolicLink(baseDir.toPath(), outside.toPath())
        listOf("", "new").forEach { version ->
            val failure = runCatching { store.packDir(version) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure?.message).isEqualTo("pack_target_invalid")
        }
        store.cleanupIfEmpty()
        assertThat(Files.isSymbolicLink(baseDir.toPath())).isTrue()
        assertThat(sentinel.readText()).isEqualTo("outside bytes")
    }

    @Test
    fun `cleanupIfEmpty preserves scratch symlink and dangling metadata entry`() = runTest {
        val outside = File(tempDir, "outside").apply { mkdirs() }
        val sentinel = File(outside, "sentinel").apply { writeText("outside bytes") }
        baseDir.mkdirs()
        val scratch = store.partialDir() // Cache the fixture entry before deliberately poisoning its identity.
        Files.createSymbolicLink(scratch.toPath(), outside.toPath())
        store.cleanupIfEmpty()
        assertThat(Files.isSymbolicLink(scratch.toPath())).isTrue()
        assertThat(sentinel.readText()).isEqualTo("outside bytes")
        assertThat(scratch.delete()).isTrue()
        val metadata = File(baseDir, "previous.json")
        Files.createSymbolicLink(metadata.toPath(), File(tempDir, "missing-metadata").toPath())
        assertThat(store.readPrevious()).isNull()
        store.cleanupIfEmpty()
        assertThat(Files.isSymbolicLink(metadata.toPath())).isTrue()
        assertThat(baseDir.exists()).isTrue()
    }

    @Test
    fun `trusted filesDir alias remains a supported root`() {
        val physical = File(tempDir, "physical").apply { mkdirs() }
        val alias = File(tempDir, "alias")
        Files.createSymbolicLink(alias.toPath(), physical.toPath())
        val context = mockk<Context>()
        every { context.filesDir } returns alias
        val aliased = PackStateStore(context)
        assertThat(aliased.packDir("1")).isEqualTo(File(alias, "mappacks/au-sa/v1"))
        assertThat(aliased.packDir("").parentFile).isEqualTo(File(alias, "mappacks/au-sa"))
    }

    @Test
    fun `partialDir rejects root nested and archive links with full no-follow preservation`() = runTest {
        store.writeCurrent(samplePack("1"))
        store.writePrevious(samplePack("2"))
        listOf("v1/tiles.mbtiles", "v2/search.db", "unknown/user.bin").forEach { path ->
            File(baseDir, path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0, -1, 42)) }
        }
        val outside = File(tempDir, "outside/user.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(42, -1, 0))
        }
        val scratch = File(baseDir, ".partial")
        val destinations = listOf(File(baseDir, "v1"), File(baseDir, "v2"), baseDir,
            outside.parentFile!!, File(tempDir, "missing"))
        destinations.forEach { destination ->
            Files.createSymbolicLink(scratch.toPath(), destination.toPath())
            assertScratchRefused()
            Files.delete(scratch.toPath())
        }
        File(scratch, "nested/empty").mkdirs()
        File(scratch, "resume.marker").writeBytes(byteArrayOf(1, 0, -1, 42))
        destinations.forEach { destination ->
            val link = File(scratch, "nested/redirect")
            Files.createSymbolicLink(link.toPath(), destination.toPath())
            assertScratchRefused()
            Files.delete(link.toPath())
        }
        (listOf(File(baseDir, "current.json"), File(baseDir, "previous.json"),
            File(baseDir, "v1/tiles.mbtiles"), File(baseDir, "v2/search.db"), outside,
            File(tempDir, "missing"))).forEach { destination ->
            val zip = File(scratch, "pack.zip")
            Files.createSymbolicLink(zip.toPath(), destination.toPath())
            assertScratchRefused()
            Files.delete(zip.toPath())
        }
        // Healthy, nonempty scratch is still resumable and cleanupIfEmpty keeps it intact.
        val before = completeSnapshot()
        assertThat(store.partialDir()).isEqualTo(scratch)
        store.cleanupIfEmpty()
        assertThat(completeSnapshot()).isEqualTo(before)
    }

    @Test
    fun `partialDir rejects substituted parent and non-directory scratch identities`() = runTest {
        File(baseDir, ".partial/resume.marker").apply { parentFile!!.mkdirs(); writeText("resume bytes") }
        File(baseDir, "unknown/user.bin").apply { parentFile!!.mkdirs(); writeText("user bytes") }
        val outside = File(tempDir, "outside").apply { mkdirs() }
        File(outside, "sentinel").writeText("outside bytes")
        listOf(baseDir, baseDir.parentFile!!).forEach { directory ->
            val retained = File(tempDir, "retained")
            Files.move(directory.toPath(), retained.toPath())
            Files.createSymbolicLink(directory.toPath(), outside.toPath())
            assertScratchRefused()
            Files.delete(directory.toPath())
            Files.move(retained.toPath(), directory.toPath())
        }
        val scratch = File(baseDir, ".partial")
        val retained = File(tempDir, "retained-scratch")
        Files.move(scratch.toPath(), retained.toPath())
        scratch.writeText("not a directory")
        assertScratchRefused()
        Files.delete(scratch.toPath())
        Files.move(retained.toPath(), scratch.toPath())
        File(scratch, "pack.zip").mkdirs()
        assertScratchRefused()
    }

    @Test
    fun `partialDir supports absent scratch and trusted root alias without creating state`() {
        val before = completeSnapshot()
        assertThat(store.partialDir()).isEqualTo(File(baseDir, ".partial"))
        assertThat(completeSnapshot()).isEqualTo(before)
        val physical = File(tempDir, "physical").apply { mkdirs() }
        val alias = File(tempDir, "alias")
        Files.createSymbolicLink(alias.toPath(), physical.toPath())
        val context = mockk<Context>()
        every { context.filesDir } returns alias
        val aliased = PackStateStore(context)
        val aliasedBefore = completeSnapshot()
        assertThat(aliased.partialDir()).isEqualTo(File(alias, "mappacks/au-sa/.partial"))
        assertThat(completeSnapshot()).isEqualTo(aliasedBefore)
    }

    private suspend fun assertScratchRefused() {
        val before = completeSnapshot()
        repeat(2) {
            val failure = runCatching { store.partialDir() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(failure?.message).isEqualTo("pack_scratch_invalid")
            assertThat(completeSnapshot()).isEqualTo(before)
            store.cleanupIfEmpty()
            assertThat(completeSnapshot()).isEqualTo(before)
        }
    }

    private data class TreeEntry(val kind: String, val bytes: List<Byte> = emptyList(),
        val link: String? = null, val identity: String? = null)

    private fun completeSnapshot(): Map<String, TreeEntry> = Files.walk(tempDir.toPath()).use { paths ->
        paths.iterator().asSequence().associate { path ->
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val entry = when {
                attributes.isSymbolicLink -> TreeEntry("link", link = Files.readSymbolicLink(path).toString())
                attributes.isDirectory -> TreeEntry("directory")
                attributes.isRegularFile -> TreeEntry("file", Files.readAllBytes(path).toList())
                else -> TreeEntry("other")
            }
            tempDir.toPath().relativize(path).toString() to entry.copy(identity = attributes.fileKey()?.toString())
        }
    }

    private fun samplePack(forVersion: String) = InstalledPack(
        version = forVersion,
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-08-22T00:00:00Z"),
        totalSizeBytes = 1L,
        tilesPath = "tiles.mbtiles",
        searchPath = null,
        routingPath = null,
        manifestSha256 = "a".repeat(64),
    )

}
