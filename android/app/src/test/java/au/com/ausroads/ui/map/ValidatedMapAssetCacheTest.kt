package au.com.ausroads.ui.map

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.rules.TemporaryFolder

class ValidatedMapAssetCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val complete = "valid-map-content".toByteArray()

    @Test fun corruptPartialIsReplacedAndHealthyCacheIsRevalidated() {
        val target = File(temporary.root, "map.db").apply { writeText("partial") }
        var validations = 0
        val validate: (File) -> Unit = {
            assertThat(it.readBytes()).isEqualTo(complete)
            validations++
        }
        ValidatedMapAssetCache.ensure(target, { ByteArrayInputStream(complete) }, validate)
        ValidatedMapAssetCache.ensure(target, { ByteArrayInputStream(complete) }, validate)
        assertThat(target.readBytes()).isEqualTo(complete)
        assertThat(validations).isEqualTo(2)
    }

    @Test fun halfwayCopyPreservesPreviousFileAndRetryPublishesCompleteAsset() {
        val target = File(temporary.root, "map.db").apply { writeText("old-map") }
        var opens = 0
        val source = {
            opens++
            if (opens == 2) object : InputStream() {
                private var count = 0
                override fun read(): Int {
                    if (count++ < 4) return 65
                    throw IOException("controlled halfway copy")
                }
            } else ByteArrayInputStream(complete)
        }
        assertThrows(IOException::class.java) {
            ValidatedMapAssetCache.ensure(target, source) { }
        }
        assertThat(target.readText()).isEqualTo("old-map")
        assertThat(temporary.root.listFiles()!!.map { it.name }).containsExactly("map.db")
        ValidatedMapAssetCache.ensure(target, { ByteArrayInputStream(complete) }) {
            assertThat(it.readBytes()).isEqualTo(complete)
        }
        assertThat(target.readBytes()).isEqualTo(complete)
    }

    @Test fun sourceChangesBetweenHashAndCopyAreNotPublished() {
        val target = File(temporary.root, "map.db").apply { writeText("old-map") }
        var opens = 0
        val error = assertThrows(IllegalStateException::class.java) {
            ValidatedMapAssetCache.ensure(target, {
                ByteArrayInputStream(if (++opens == 1) complete else "changed-map".toByteArray())
            }) { }
        }
        assertThat(error.message).contains("seam=asset-cache-bytes class=contract-drift")
        assertThat(target.readText()).isEqualTo("old-map")
        assertThat(temporary.root.listFiles()!!.map { it.name }).containsExactly("map.db")
    }

    @Test fun semanticValidationFailureNeverPublishesMatchingButInvalidBytes() {
        val target = File(temporary.root, "map.db").apply { writeText("old-map") }
        assertThrows(IllegalStateException::class.java) {
            ValidatedMapAssetCache.ensure(target, { ByteArrayInputStream(complete) }) {
                error("controlled invalid SQLite schema")
            }
        }
        assertThat(target.readText()).isEqualTo("old-map")
        assertThat(temporary.root.listFiles()!!.map { it.name }).containsExactly("map.db")
    }

    @Test fun symlinkTargetCannotOverwriteOtherData() {
        val owned = File(temporary.root, "owned.db").apply { writeText("saved-user-data") }
        val link = File(temporary.root, "map.db")
        Files.createSymbolicLink(link.toPath(), owned.toPath())
        val error = assertThrows(IllegalArgumentException::class.java) {
            ValidatedMapAssetCache.ensure(link, { ByteArrayInputStream(complete) }) { }
        }
        assertThat(error.message).contains("seam=asset-cache-path class=contract-drift")
        assertThat(owned.readText()).isEqualTo("saved-user-data")
    }

    @Test fun ordinaryCanonicalParentAliasIsSupported() {
        val directory = temporary.newFolder("actual")
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), directory.toPath())
        val result = ValidatedMapAssetCache.ensure(File(alias, "map.db"), { ByteArrayInputStream(complete) }) { }
        assertThat(result.canonicalFile).isEqualTo(File(directory, "map.db").canonicalFile)
        assertThat(result.readBytes()).isEqualTo(complete)
    }
}
