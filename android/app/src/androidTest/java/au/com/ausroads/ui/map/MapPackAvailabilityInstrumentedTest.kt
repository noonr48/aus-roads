package au.com.ausroads.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the renderer can consume a pack installed by the downloader: given a
 * `current.json` + versioned tiles file under filesDir (the exact layout
 * PackStateStore writes), [MapPackAvailability.installedPackMbtiles] resolves to
 * that file and [MapPackAvailability.hasAnyPack] is true. Fully hermetic — writes
 * a synthetic pack into the app's own filesDir, no network and no assets.
 */
@RunWith(AndroidJUnit4::class)
class MapPackAvailabilityInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val baseDir = File(context.filesDir, "mappacks/au-sa")

    @After
    fun cleanUp() {
        File(baseDir, "current.json").delete()
        File(baseDir, "v$VERSION").deleteRecursively()
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
        assertThat(resolved!!.absolutePath).isEqualTo(tiles.absolutePath)
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
