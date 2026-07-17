package au.com.ausroads.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.offline.search.FtsSearchRepository
import au.com.ausroads.offline.search.PoiCategory
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the exact APK asset opens and returns real names/categories in Android SQLite. */
@RunWith(AndroidJUnit4::class)
class BundledSearchInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = File(context.cacheDir, "bundled-search-test.db")
    private lateinit var repository: FtsSearchRepository

    @Before
    fun setUp() {
        context.assets.open("maptile/adelaide-test-search-v1.db").use { input ->
            database.outputStream().use { output -> input.copyTo(output) }
        }
        repository = FtsSearchRepository().also { it.open(database.absolutePath) }
    }

    @After
    fun tearDown() {
        repository.close()
        database.delete()
    }

    @Test
    fun bundledIndex_supportsNamesAliasesAndNearbySupermarkets() = runBlocking {
        assertThat(repository.isReady).isTrue()
        assertThat(repository.search("Adelaide")).isNotEmpty()
        assertThat(repository.search("supermarket")).isNotEmpty()
        assertThat(
            repository.nearestByCategory(
                latitude = -34.911,
                longitude = 138.654,
                category = PoiCategory.SUPERMARKET,
            ),
        ).isNotEmpty()
    }
}
