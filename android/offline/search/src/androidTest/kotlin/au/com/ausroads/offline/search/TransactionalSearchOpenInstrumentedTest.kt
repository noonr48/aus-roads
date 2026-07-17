package au.com.ausroads.offline.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransactionalSearchOpenInstrumentedTest {
    private lateinit var directory: File
    private lateinit var repository: FtsSearchRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "search-swap-${UUID.randomUUID()}").apply { mkdirs() }
        repository = FtsSearchRepository()
    }

    @After
    fun tearDown() {
        repository.close()
        directory.deleteRecursively()
    }

    @Test
    fun invalidReplacementLeavesPreviouslyOpenedIndexUsable() = runBlocking {
        val valid = File(directory, "valid.db")
        SQLiteDatabase.openOrCreateDatabase(valid, null).use { database ->
            database.execSQL(
                "CREATE TABLE search_meta " +
                    "(id INTEGER PRIMARY KEY, name TEXT, kind TEXT, class TEXT, lat REAL, lon REAL)",
            )
            database.execSQL(
                "INSERT INTO search_meta VALUES (1, 'Old Generation Store', " +
                    "'poi', 'shop=supermarket', -34.9, 138.6)",
            )
        }
        repository.open(valid.absolutePath)
        assertThat(repository.search("Old Generation")).hasSize(1)

        val invalid = File(directory, "invalid.db").apply { writeText("not a SQLite database") }
        repository.open(invalid.absolutePath)

        assertThat(repository.isReady).isTrue()
        assertThat(repository.search("Old Generation").single().name)
            .isEqualTo("Old Generation Store")
    }

    @Test
    fun incompleteSearchMetaSchemaLeavesPreviouslyOpenedIndexUsable() = runBlocking {
        val valid = buildSearchDatabase("valid-schema.db")
        repository.open(valid.absolutePath)

        val incomplete = File(directory, "incomplete-schema.db")
        SQLiteDatabase.openOrCreateDatabase(incomplete, null).use { database ->
            database.execSQL(
                "CREATE TABLE search_meta " +
                    "(id INTEGER PRIMARY KEY, name TEXT, kind TEXT, lat REAL, lon REAL)",
            )
            database.execSQL(
                "INSERT INTO search_meta VALUES (1, 'Broken Store', 'poi', -34.9, 138.6)",
            )
        }

        repository.open(incomplete.absolutePath)

        assertThat(repository.isReady).isTrue()
        assertThat(repository.search("Old Generation").single().name)
            .isEqualTo("Old Generation Store")
    }

    @Test
    fun nonFiniteCoordinatesLeavePreviouslyOpenedIndexUsable() = runBlocking {
        val valid = buildSearchDatabase("valid-coordinates.db")
        repository.open(valid.absolutePath)

        val invalid = File(directory, "non-finite-coordinates.db")
        SQLiteDatabase.openOrCreateDatabase(invalid, null).use { database ->
            createSearchMeta(database)
            database.execSQL(
                "INSERT INTO search_meta VALUES " +
                    "(1, 'Infinite Store', 'poi', 'shop=supermarket', 1e999, 138.6)",
            )
        }

        repository.open(invalid.absolutePath)

        assertThat(repository.isReady).isTrue()
        assertThat(repository.search("Old Generation").single().name)
            .isEqualTo("Old Generation Store")
    }

    private fun buildSearchDatabase(filename: String): File = File(directory, filename).also { file ->
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            createSearchMeta(database)
            database.execSQL(
                "INSERT INTO search_meta VALUES (1, 'Old Generation Store', " +
                    "'poi', 'shop=supermarket', -34.9, 138.6)",
            )
        }
    }

    private fun createSearchMeta(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE search_meta " +
                "(id INTEGER PRIMARY KEY, name TEXT, kind TEXT, class TEXT, lat REAL, lon REAL)",
        )
    }
}
