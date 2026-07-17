package au.com.ausroads.di

import android.content.Context
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.routing.engine.valhalla.PreparedRoutingEngine
import au.com.ausroads.routing.engine.valhalla.ValhallaRoutingEngine
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoutingInitializerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `current descriptor selects its custom routing path instead of newer stale data`() {
        val filesDir = temporaryFolder.newFolder("current-files")
        val base = File(filesDir, "mappacks/au-sa").apply { mkdirs() }
        val selected = File(base, "v2/graphs/current.tar").apply {
            parentFile?.mkdirs()
            writeText("current")
        }
        File(base, "v9/routing/valhalla_tiles.tar").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        writeCurrent(base, version = "2", routingPath = "graphs/current.tar")

        assertThat(resolveInstalledRoutingTar(filesDir)).isEqualTo(selected)
    }

    @Test
    fun `current pack without routing does not reuse stale generation`() {
        val filesDir = temporaryFolder.newFolder("no-routing-files")
        val base = File(filesDir, "mappacks/au-sa").apply { mkdirs() }
        File(base, "v9/routing/valhalla_tiles.tar").apply {
            parentFile?.mkdirs()
            writeText("stale")
        }
        writeCurrent(base, version = "2", routingPath = null)

        assertThat(resolveInstalledRoutingTar(filesDir)).isNull()
    }

    @Test
    fun `malformed or blank current sentinel cannot select legacy routing`() {
        listOf(
            "not-json \"version\":\"2\"",
            currentJson(version = "", routingPath = "routing/valhalla_tiles.tar"),
        ).forEachIndexed { index, sentinel ->
            val filesDir = temporaryFolder.newFolder("invalid-files-$index")
            val base = File(filesDir, "mappacks/au-sa").apply { mkdirs() }
            File(base, "routing/valhalla_tiles.tar").apply {
                parentFile?.mkdirs()
                writeText("legacy")
            }
            File(base, "current.json").writeText(sentinel)

            assertThat(resolveInstalledRoutingTar(filesDir)).isNull()
        }
    }

    @Test
    fun `missing current sentinel does not activate legacy routing beside bundled map`() {
        val filesDir = temporaryFolder.newFolder("legacy-only-files")
        val legacy = File(filesDir, "mappacks/au-sa/routing/valhalla_tiles.tar").apply {
            parentFile?.mkdirs()
            writeText("legacy")
        }

        assertThat(legacy.isFile).isTrue()
        assertThat(resolveInstalledRoutingTar(filesDir)).isNull()
    }

    @Test
    fun `missing selected routing activates an empty candidate transactionally`() {
        val filesDir = temporaryFolder.newFolder("clear-files")
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        val engine = mockk<ValhallaRoutingEngine>(relaxed = true)
        val candidate = mockk<PreparedRoutingEngine>(relaxed = true)
        every { engine.prepare(null) } returns candidate

        RoutingInitializer(context, engine).initialize()

        verify(exactly = 1) { engine.prepare(null) }
        verify(exactly = 1) { engine.activate(candidate) }
        verify(exactly = 1) { candidate.close() }
        verify(exactly = 0) { engine.clear() }
        verify(exactly = 0) { engine.initialize(any()) }
    }

    @Test
    fun `preparing a declared but missing routing component leaves active engine untouched`() {
        val filesDir = temporaryFolder.newFolder("missing-prepared-routing")
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        val engine = mockk<ValhallaRoutingEngine>(relaxed = true)
        val descriptor = installedPack(routingPath = "routing/missing.tar")

        val failure = runCatching { RoutingInitializer(context, engine).prepare(descriptor) }

        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { engine.clear() }
        verify(exactly = 0) { engine.initialize(any()) }
        verify(exactly = 0) { engine.prepare(any()) }
    }

    private fun writeCurrent(base: File, version: String, routingPath: String?): File =
        File(base, "current.json").apply { writeText(currentJson(version, routingPath)) }

    private fun installedPack(routingPath: String?) = InstalledPack(
        version = "2",
        regionCode = "AU-SA",
        installedAt = Instant.parse("2026-07-16T00:00:00Z"),
        totalSizeBytes = 1,
        tilesPath = "tiles.mbtiles",
        searchPath = "search.db",
        routingPath = routingPath,
        manifestSha256 = "test",
    )

    private fun currentJson(version: String, routingPath: String?): String {
        val routingJson = routingPath?.let { "\"$it\"" } ?: "null"
        return """
            {
              "version":"$version",
              "regionCode":"AU-SA",
              "installedAt":"2026-07-16T00:00:00Z",
              "totalSizeBytes":1,
              "tilesPath":"tiles.mbtiles",
              "searchPath":"search.db",
              "routingPath":$routingJson,
              "manifestSha256":"test"
            }
        """.trimIndent()
    }
}
