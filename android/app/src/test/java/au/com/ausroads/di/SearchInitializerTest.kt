package au.com.ausroads.di

import android.content.Context
import com.google.common.truth.Truth.assertThat
import au.com.ausroads.io.isExpectedSqliteDatabase
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.search.FtsSearchRepository
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchInitializerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `current descriptor selects its version and custom search path`() {
        val filesDir = temporaryFolder.newFolder("files")
        val base = File(filesDir, "mappacks/au-sa").apply { mkdirs() }
        val currentDatabase = File(base, "v2/indexes/current.db").apply {
            parentFile?.mkdirs()
            writeText("db")
        }
        File(base, "v9/search.db").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        writeCurrent(base, version = "2", searchPath = "indexes/current.db")

        assertThat(resolveInstalledSearchDatabase(filesDir)).isEqualTo(currentDatabase)
    }

    @Test
    fun `missing current database does not fall through to stale legacy data`() {
        val filesDir = temporaryFolder.newFolder("files")
        val base = File(filesDir, "mappacks/au-sa").apply { mkdirs() }
        File(base, "search.db").writeText("stale")
        writeCurrent(base, version = "2", searchPath = "missing.db")

        assertThat(resolveInstalledSearchDatabase(filesDir)).isNull()
        assertThat(hasInstalledPackDescriptor(filesDir)).isTrue()
    }

    @Test
    fun `bundled fallback is a nonempty SQLite database`() {
        val asset = File("src/main/assets/maptile/adelaide-test-search-v1.db")
        val expectedHash = File("${asset.path}.sha256").readText().trim().substringBefore(' ')

        assertThat(asset.exists()).isTrue()
        assertThat(asset.length()).isGreaterThan(1_000_000L)
        assertThat(isExpectedSqliteDatabase(asset, expectedHash)).isTrue()
    }

    @Test
    fun `blank installed sentinel does not block bundled fallback`() {
        val filesDir = temporaryFolder.newFolder("blank-files")
        val base = File(filesDir, "mappacks/au-sa").apply { mkdirs() }
        val current = writeCurrent(base, version = "", searchPath = "search.db")

        assertThat(current.isFile).isTrue()
        assertThat(hasInstalledPackDescriptor(filesDir)).isFalse()
    }

    @Test
    fun `malformed sentinel cannot select installed search independently of the map`() {
        val filesDir = temporaryFolder.newFolder("malformed-files")
        val base = File(filesDir, "mappacks/au-sa").apply { mkdirs() }
        File(base, "v2/search.db").apply {
            parentFile?.mkdirs()
            writeText("installed")
        }
        File(base, "current.json").writeText("not-json \"version\":\"2\"")

        assertThat(hasInstalledPackDescriptor(filesDir)).isFalse()
        assertThat(resolveInstalledSearchDatabase(filesDir)).isNull()
    }

    @Test
    fun `incomplete sentinel is rejected by the canonical installed-pack schema`() {
        val filesDir = temporaryFolder.newFolder("incomplete-files")
        val current = File(filesDir, "mappacks/au-sa/current.json").apply {
            parentFile?.mkdirs()
            writeText("""{"version":"2"}""")
        }

        assertThat(current.isFile).isTrue()
        assertThat(hasInstalledPackDescriptor(filesDir)).isFalse()
        assertThat(resolveInstalledSearchDatabase(filesDir)).isNull()
    }

    @Test
    fun `missing sentinel does not select legacy search beside the bundled map`() {
        val filesDir = temporaryFolder.newFolder("legacy-only-files")
        val legacy = File(filesDir, "mappacks/au-sa/search.db").apply {
            parentFile?.mkdirs()
            writeText("legacy")
        }

        assertThat(legacy.isFile).isTrue()
        assertThat(resolveInstalledSearchDatabase(filesDir)).isNull()
    }

    @Test
    fun `corrupt bundled cache is rejected even when nonempty`() {
        val corrupt = temporaryFolder.newFile("search.db").apply {
            writeText("SQLite format 3\u0000this is not the bundled database")
        }

        assertThat(isExpectedSqliteDatabase(corrupt, "0".repeat(64))).isFalse()
    }

    @Test
    fun `preparing a missing installed search component leaves active repository untouched`() {
        val filesDir = temporaryFolder.newFolder("missing-prepared-search")
        val context = mockk<Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns filesDir
        val repository = mockk<FtsSearchRepository>(relaxed = true)
        val descriptor = InstalledPack(
            version = "2",
            regionCode = "AU-SA",
            installedAt = Instant.parse("2026-07-16T00:00:00Z"),
            totalSizeBytes = 1,
            tilesPath = "tiles.mbtiles",
            searchPath = "missing.db",
            routingPath = null,
            manifestSha256 = "test",
        )

        val failure = runCatching { SearchInitializer(context, repository).prepare(descriptor) }

        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { repository.close() }
        verify(exactly = 0) { repository.open(any()) }
        verify(exactly = 0) { repository.prepare(any()) }
        verify(exactly = 0) { repository.activate(any()) }
    }

    private fun writeCurrent(base: File, version: String, searchPath: String): File =
        File(base, "current.json").apply {
            writeText(
                """
                {
                  "version":"$version",
                  "regionCode":"AU-SA",
                  "installedAt":"2026-07-16T00:00:00Z",
                  "totalSizeBytes":1,
                  "tilesPath":"tiles.mbtiles",
                  "searchPath":"$searchPath",
                  "routingPath":null,
                  "manifestSha256":"test"
                }
                """.trimIndent(),
            )
        }
}
