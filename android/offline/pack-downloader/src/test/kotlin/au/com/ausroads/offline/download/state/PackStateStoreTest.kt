package au.com.ausroads.offline.download.state

import android.content.Context
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
        tempDir.deleteRecursively()
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
