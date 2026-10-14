package au.com.ausroads.ui.map

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * Proves the renderer can consume a pack installed by the downloader: given a
 * `current.json` + versioned tiles file under filesDir (the exact layout
 * PackStateStore writes), [MapPackAvailability.installedPackMbtiles] resolves to
 * that file and [MapPackAvailability.hasAnyPack] is true. Fully hermetic — writes
 * a synthetic pack into an isolated ContextWrapper filesDir, never the app's
 * adopted pack pointer. No network, asset replacement or owner-data cleanup.
 */
@RunWith(AndroidJUnit4::class)
class MapPackAvailabilityInstrumentedTest {

    private lateinit var context: Context
    private var fixture: File? = null
    private val baseDir get() = File(context.filesDir, "mappacks/au-sa")

    @Before
    fun isolateFiles() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(application.cacheDir.toPath(), "pack-availability-").toFile()
        fixture = root
        context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
        }
        assertThat(context.filesDir.canonicalPath).isNotEqualTo(application.filesDir.canonicalPath)
        assertThat(baseDir.mkdirs()).isTrue()
    }

    @After
    fun cleanUp() {
        // Only a directory this test created; a failed setup cannot target real filesDir.
        fixture?.deleteRecursively()
        fixture = null
    }

    @Test
    fun installedPackMbtiles_resolvesVersionedTilesFromCurrentJson() {
        val packDir = File(baseDir, "v$VERSION").apply { mkdirs() }
        val tiles = File(packDir, "tiles.mbtiles").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        File(baseDir, "current.json").writeText(
            """{"version":"$VERSION","regionCode":"AU-SA","installedAt":"2026-06-01T00:00:00Z",""" +
                """"totalSizeBytes":4,"tilesPath":"tiles.mbtiles","manifestSha256":""}""",
        )

        val resolved = MapPackAvailability.installedPackMbtiles(context)

        assertThat(resolved).isNotNull()
        // Android's /data/user/0 and /data/data names alias the same app directory.
        // The resolver deliberately returns a canonical, containment-checked file.
        assertThat(resolved!!.canonicalFile).isEqualTo(tiles.canonicalFile)
        assertThat(MapPackAvailability.hasAnyPack(context)).isTrue()
    }

    @Test
    fun installedPackMbtiles_isNull_whenNoCurrentJson() {
        File(baseDir, "current.json").delete()
        assertThat(MapPackAvailability.installedPackMbtiles(context)).isNull()
    }

    private companion object {
        private const val VERSION = "9999-01-01-test"
    }
}
