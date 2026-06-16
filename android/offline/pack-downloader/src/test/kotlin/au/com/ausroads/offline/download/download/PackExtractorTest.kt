package au.com.ausroads.offline.download.download

import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackExtractorTest {

    private lateinit var tempDir: File
    private lateinit var extractor: PackExtractor

    @Before
    fun setUp() {
        tempDir = createTempDir("pack-extractor-test")
        extractor = PackExtractor()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `extract unpacks files from zip`() {
        val zipFile = File(tempDir, "pack.zip")
        val targetDir = File(tempDir, "output")

        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("tiles.mbtiles"))
            zos.write("tile-data".toByteArray())
            zos.closeEntry()
        }

        kotlinx.coroutines.test.runTest {
            extractor.extract(zipFile, targetDir)
        }

        val extracted = File(targetDir, "tiles.mbtiles")
        assertThat(extracted.exists()).isTrue()
        assertThat(extracted.readText()).isEqualTo("tile-data")
    }

    @Test
    fun `extract creates subdirectories for nested entries`() {
        val zipFile = File(tempDir, "nested.zip")
        val targetDir = File(tempDir, "output-nested")

        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("data/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("data/search.db"))
            zos.write("search-data".toByteArray())
            zos.closeEntry()
        }

        kotlinx.coroutines.test.runTest {
            extractor.extract(zipFile, targetDir)
        }

        val extracted = File(targetDir, "data/search.db")
        assertThat(extracted.exists()).isTrue()
        assertThat(extracted.readText()).isEqualTo("search-data")
    }

    @Test
    fun `extract handles empty zip without error`() {
        val zipFile = File(tempDir, "empty.zip")
        val targetDir = File(tempDir, "output-empty")

        ZipOutputStream(zipFile.outputStream()).use { zos ->
            // no entries
        }

        kotlinx.coroutines.test.runTest {
            extractor.extract(zipFile, targetDir)
        }

        assertThat(targetDir.exists()).isTrue()
        assertThat(targetDir.listFiles()).isEmpty()
    }

    @Test
    fun `extract cleans up target dir on failure`() {
        // Use a non-existent path as the zip file to guarantee FileInputStream throws
        val zipFile = File(tempDir, "nonexistent.zip")
        val targetDir = File(tempDir, "output-bad")
        targetDir.mkdirs()

        var threw = false
        try {
            kotlinx.coroutines.test.runTest {
                extractor.extract(zipFile, targetDir)
            }
        } catch (_: Exception) {
            threw = true
        }

        assertThat(threw).isTrue()
        assertThat(targetDir.exists()).isFalse()
    }

    @Test
    fun `extract preserves file content for multiple entries`() {
        val zipFile = File(tempDir, "multi.zip")
        val targetDir = File(tempDir, "output-multi")

        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("alpha".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("b.txt"))
            zos.write("beta".toByteArray())
            zos.closeEntry()
        }

        kotlinx.coroutines.test.runTest {
            extractor.extract(zipFile, targetDir)
        }

        assertThat(File(targetDir, "a.txt").readText()).isEqualTo("alpha")
        assertThat(File(targetDir, "b.txt").readText()).isEqualTo("beta")
    }
}
