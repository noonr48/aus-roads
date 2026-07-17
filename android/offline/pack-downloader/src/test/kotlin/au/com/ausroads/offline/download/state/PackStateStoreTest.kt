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
            directoryName = "v2026.06.01-generation-a",
        )

        val json = Json.encodeToString(InstalledPack.serializer(), pack)
        val decoded = Json.decodeFromString(InstalledPack.serializer(), json)

        assertThat(decoded.version).isEqualTo("2026.06.01")
        assertThat(decoded.regionCode).isEqualTo("AU-SA")
        assertThat(decoded.totalSizeBytes).isEqualTo(150_000_000)
        assertThat(decoded.searchPath).isEqualTo("search.db")
        assertThat(decoded.routingPath).isNull()
        assertThat(decoded.directoryName).isEqualTo("v2026.06.01-generation-a")
    }

    @Test
    fun `legacy descriptor without directory uses version path`() {
        val pack = InstalledPack(
            version = "2026.06.01",
            regionCode = "AU-SA",
            installedAt = Instant.fromEpochMilliseconds(1717200000000),
            totalSizeBytes = 1,
            tilesPath = "tiles.mbtiles",
            manifestSha256 = "test",
        )

        assertThat(pack.installDirectoryName()).isEqualTo("v2026.06.01")
        assertThat(store.packDir(pack)).isEqualTo(File(baseDir, "v2026.06.01"))
    }

    @Test
    fun `generation directories distinguish repeated installs of one version`() {
        val first = store.generationDir("1", "generation-a")
        val second = store.generationDir("1", "generation-b")

        assertThat(first).isNotEqualTo(second)
        assertThat(first.parentFile).isEqualTo(baseDir)
        assertThat(second.parentFile).isEqualTo(baseDir)
    }

    @Test
    fun `current descriptor replacement leaves no partial json`() = runTest {
        val first = installedPack(version = "1", directoryName = "v1-a")
        val second = installedPack(version = "1", directoryName = "v1-b")

        store.writeCurrent(first)
        store.writeCurrent(second)

        assertThat(store.readCurrent()).isEqualTo(second)
        assertThat(File(baseDir, ".current.json.tmp").exists()).isFalse()
    }

    @Test
    fun `readCurrent rejects a blank pack version`() = runTest {
        store.writeCurrent(
            InstalledPack(
                version = "",
                regionCode = "AU-SA",
                installedAt = Instant.fromEpochMilliseconds(1717200000000),
                totalSizeBytes = 1,
                tilesPath = "tiles.mbtiles",
                searchPath = "search.db",
                routingPath = null,
                manifestSha256 = "test",
            ),
        )

        assertThat(store.readCurrent()).isNull()
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

    private fun installedPack(version: String, directoryName: String) = InstalledPack(
        version = version,
        regionCode = "AU-SA",
        installedAt = Instant.fromEpochMilliseconds(1717200000000),
        totalSizeBytes = 1,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "test",
        directoryName = directoryName,
    )
}
