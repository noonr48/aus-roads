package au.com.ausroads.di

import au.com.ausroads.di.InstalledPackPaths.RoutingSelection
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstalledPackPathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `only adopted version is resolved not a newer orphan`() {
        val base = temporary.newFolder("packs")
        val adopted = create(base, "v2/custom/catalog.db")
        create(base, "v999/search.db")
        assertThat(InstalledPackPaths.resolve(base, "2", "custom/catalog.db"))
            .isEqualTo(adopted.canonicalFile)
        assertThat(InstalledPackPaths.resolve(base, "2", "search.db")).isNull()
    }

    @Test
    fun `blank absolute traversal and invalid versions are rejected`() {
        val base = temporary.newFolder("packs")
        val outside = create(base, "outside.db")
        assertThat(InstalledPackPaths.resolve(base, "2", "../outside.db")).isNull()
        assertThat(InstalledPackPaths.resolve(base, "2", outside.absolutePath)).isNull()
        assertThat(InstalledPackPaths.resolve(base, "../bad", "search.db")).isNull()
        assertThat(InstalledPackPaths.resolve(base, "", "search.db")).isNull()
        assertThat(InstalledPackPaths.resolve(base, "2", "")).isNull()
    }

    @Test
    fun `symlink escape is rejected by canonical containment`() {
        val base = temporary.newFolder("packs")
        val adopted = File(base, "v2").apply { mkdirs() }
        val outside = temporary.newFile("outside.db").apply { writeText("fixture") }
        Files.createSymbolicLink(File(adopted, "search.db").toPath(), outside.toPath())
        assertThat(InstalledPackPaths.resolve(base, "2", "search.db")).isNull()
    }

    @Test
    fun `empty files and directories are not catalog components`() {
        val base = temporary.newFolder("packs")
        val adopted = File(base, "v2").apply { mkdirs() }
        File(adopted, "empty.db").createNewFile()
        File(adopted, "directory.db").mkdir()
        assertThat(InstalledPackPaths.resolve(base, "2", "empty.db")).isNull()
        assertThat(InstalledPackPaths.resolve(base, "2", "directory.db")).isNull()
    }

    @Test
    fun `routing selects custom adopted tar never conventional or newest orphan`() {
        val base = temporary.newFolder("packs")
        val adopted = create(base, "v2/custom/tiles.tar")
        create(base, "routing/valhalla_tiles.tar")
        create(base, "v999/routing/valhalla_tiles.tar")
        assertThat(InstalledPackPaths.resolveRouting(base, "2", "custom/tiles.tar"))
            .isEqualTo(RoutingSelection.Available(adopted.canonicalFile))
        assertThat(InstalledPackPaths.resolveRouting(base, "2", "valhalla_tiles.tar"))
            .isEqualTo(RoutingSelection.Rejected)
    }

    @Test
    fun `routing absent path is supported even when conventional tar exists`() {
        val base = temporary.newFolder("packs")
        create(base, "v2/valhalla_tiles.tar")
        assertThat(InstalledPackPaths.resolveRouting(base, "2", null))
            .isEqualTo(RoutingSelection.Unavailable)
    }

    @Test
    fun `routing rejects non string metadata blank paths and invalid versions`() {
        val base = temporary.newFolder("packs")
        create(base, "v2/tiles.tar")
        listOf<Any?>(null, 2, true, "", "../2").forEach { version ->
            assertThat(InstalledPackPaths.resolveRouting(base, version, "tiles.tar"))
                .isEqualTo(RoutingSelection.Rejected)
        }
        listOf<Any>(2, true, listOf("tiles.tar"), "", "  ").forEach { path ->
            assertThat(InstalledPackPaths.resolveRouting(base, "2", path))
                .isEqualTo(RoutingSelection.Rejected)
        }
        assertThat(InstalledPackPaths.resolveRouting(base, "../2", null))
            .isEqualTo(RoutingSelection.Rejected)
    }

    @Test
    fun `declared missing empty directory or escaping routing components are rejected`() {
        val base = temporary.newFolder("packs")
        val adopted = File(base, "v2").apply { mkdirs() }
        File(adopted, "empty.tar").createNewFile()
        File(adopted, "directory.tar").mkdir()
        val outside = create(base, "outside.tar")
        listOf("missing.tar", "empty.tar", "directory.tar", "../outside.tar", outside.path,
            "custom\\tiles.tar", "tiles\u0000.tar").forEach { path ->
            assertThat(InstalledPackPaths.resolveRouting(base, "2", path))
                .isEqualTo(RoutingSelection.Rejected)
        }
    }

    @Test
    fun `routing rejects even in root component directory and version symlinks`() {
        val base = temporary.newFolder("packs")
        val file = create(base, "v2/custom/tiles.tar")
        val adopted = file.parentFile!!.parentFile!!
        Files.createSymbolicLink(File(adopted, "alias.tar").toPath(), file.toPath())
        Files.createSymbolicLink(File(adopted, "alias").toPath(), file.parentFile!!.toPath())
        Files.createSymbolicLink(File(base, "v3").toPath(), adopted.toPath())
        listOf("alias.tar", "alias/tiles.tar", "alias/../custom/tiles.tar").forEach { path ->
            assertThat(InstalledPackPaths.resolveRouting(base, "2", path))
                .isEqualTo(RoutingSelection.Rejected)
        }
        assertThat(InstalledPackPaths.resolveRouting(base, "3", "custom/tiles.tar"))
            .isEqualTo(RoutingSelection.Rejected)
        val aliasBase = File(temporary.root, "alias-packs")
        Files.createSymbolicLink(aliasBase.toPath(), base.toPath())
        assertThat(InstalledPackPaths.resolveRouting(aliasBase, "2", "custom/tiles.tar"))
            .isEqualTo(RoutingSelection.Rejected)
        // The existing map/search resolver semantics remain unchanged.
        assertThat(InstalledPackPaths.resolve(base, "2", "alias.tar"))
            .isEqualTo(file.canonicalFile)
    }

    private fun create(base: File, name: String): File =
        File(base, name).apply {
            parentFile!!.mkdirs()
            writeText("fixture")
        }
}
