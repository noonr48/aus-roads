package au.com.ausroads.feature.search

import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeSearchRepository
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeSearchRepository()
        viewModel = SearchViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `query starts empty`() = runTest {
        assertThat(viewModel.query.value).isEmpty()
    }

    @Test
    fun `results start empty`() = runTest {
        assertThat(viewModel.results.value).isEmpty()
    }

    @Test
    fun `onQueryChange updates query`() = runTest {
        viewModel.onQueryChange("Sydney")
        assertThat(viewModel.query.value).isEqualTo("Sydney")
    }

    @Test
    fun `onClear resets query and results`() = runTest {
        viewModel.onQueryChange("test")
        advanceUntilIdle()
        viewModel.onClear()
        assertThat(viewModel.query.value).isEmpty()
        assertThat(viewModel.results.value).isEmpty()
    }

    @Test
    fun `onActiveChange updates isActive`() = runTest {
        viewModel.onActiveChange(true)
        assertThat(viewModel.isActive.value).isTrue()
        viewModel.onActiveChange(false)
        assertThat(viewModel.isActive.value).isFalse()
    }

    @Test
    fun `onResultSelected deactivates search`() = runTest {
        viewModel.onActiveChange(true)
        val result = SearchResult("Sydney", "city", null, -33.86, 151.20)
        viewModel.onResultSelected(result)
        assertThat(viewModel.isActive.value).isFalse()
    }

    @Test
    fun `blank query clears results`() = runTest {
        viewModel.onQueryChange("Sydney")
        advanceUntilIdle()
        viewModel.onQueryChange("")
        advanceUntilIdle()
        assertThat(viewModel.results.value).isEmpty()
    }

    @Test
    fun `debounced query triggers search`() = runTest {
        repository.stubbedResults = listOf(
            SearchResult("Melbourne", "city", null, -37.81, 144.96),
        )
        viewModel.onQueryChange("Mel")
        advanceUntilIdle()
        assertThat(viewModel.results.value).hasSize(1)
        assertThat(viewModel.results.value.first().name).isEqualTo("Melbourne")
    }

    @Test
    fun `search failure surfaces error state and clears results`() = runTest {
        repository.throwOnSearch = true
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        assertThat(viewModel.searchFailed.value).isTrue()
        assertThat(viewModel.results.value).isEmpty()
        assertThat(viewModel.isSearching.value).isFalse()
    }
}

private class FakeSearchRepository : SearchRepository {

    var stubbedResults: List<SearchResult> = emptyList()
    var throwOnSearch: Boolean = false

    override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> {
        if (throwOnSearch) throw RuntimeException("simulated index failure")
        return stubbedResults.take(limit)
    }

    override suspend fun nearest(
        latitude: Double,
        longitude: Double,
        kind: String?,
        maxDistanceDegrees: Double,
    ): SearchResult? = stubbedResults.firstOrNull()

    override suspend fun browseByCategory(category: PoiCategory, limit: Int): List<SearchResult> =
        emptyList()

    override suspend fun nearestByCategory(
        latitude: Double,
        longitude: Double,
        category: PoiCategory,
        limit: Int,
        maxDistanceDegrees: Double,
    ): List<SearchResult> = emptyList()

    override suspend fun maxspeedNear(
        latitude: Double,
        longitude: Double,
        maxDistanceDegrees: Double,
    ): Int? = null

    override fun open(dbPath: String) {}

    override fun close() {}
}
