package au.com.ausroads.feature.search

import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `onResultSelected deactivates and dismisses search`() = runTest {
        viewModel.onActiveChange(true)
        viewModel.onQueryChange("Sydney")
        val result = SearchResult("Sydney", "city", null, -33.86, 151.20)
        viewModel.onResultSelected(result)
        assertThat(viewModel.isActive.value).isFalse()
        assertThat(viewModel.query.value).isEmpty()
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
    fun `nonblank query edit immediately clears old results and enters pending state`() = runTest {
        repository.resultsByQuery["Old"] = listOf(
            SearchResult("Old result", "place", null, -34.0, 138.0),
        )
        viewModel.onQueryChange("Old")
        advanceUntilIdle()
        assertThat(viewModel.results.value.map { it.name }).containsExactly("Old result")

        viewModel.onQueryChange("New")

        assertThat(viewModel.query.value).isEqualTo("New")
        assertThat(viewModel.results.value).isEmpty()
        assertThat(viewModel.isSearching.value).isTrue()
        assertThat(viewModel.searchFailed.value).isFalse()
        assertThat(viewModel.searchUnavailable.value).isFalse()
    }

    @Test
    fun `old query finishing inside new debounce cannot republish stale results`() = runTest {
        repository.resultsByQuery["Old"] = listOf(
            SearchResult("Old result", "place", null, -34.0, 138.0),
        )
        repository.resultsByQuery["New"] = listOf(
            SearchResult("New result", "place", null, -35.0, 139.0),
        )
        repository.delayByQuery["Old"] = 100L

        viewModel.onQueryChange("Old")
        advanceTimeBy(301L)
        runCurrent()
        viewModel.onQueryChange("New")
        advanceTimeBy(101L)
        runCurrent()

        assertThat(viewModel.results.value).isEmpty()
        assertThat(viewModel.isSearching.value).isTrue()

        advanceUntilIdle()
        assertThat(viewModel.results.value.map { it.name }).containsExactly("New result")
        assertThat(viewModel.isSearching.value).isFalse()
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

    @Test
    fun `retry reruns an unchanged failed query and publishes recovered results`() = runTest {
        repository.throwOnSearch = true
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        assertThat(repository.searchQueries).containsExactly("Adelaide")
        assertThat(viewModel.searchFailed.value).isTrue()

        repository.throwOnSearch = false
        repository.stubbedResults = listOf(
            SearchResult("Adelaide", "city", null, -34.9285, 138.6007),
        )
        viewModel.onRetry()

        assertThat(viewModel.query.value).isEqualTo("Adelaide")
        assertThat(viewModel.isSearching.value).isTrue()
        assertThat(viewModel.searchFailed.value).isFalse()

        advanceUntilIdle()

        assertThat(repository.searchQueries).containsExactly("Adelaide", "Adelaide").inOrder()
        assertThat(viewModel.results.value.map { it.name }).containsExactly("Adelaide")
        assertThat(viewModel.isSearching.value).isFalse()
        assertThat(viewModel.searchFailed.value).isFalse()
    }

    @Test
    fun `retry does nothing when there is no query`() = runTest {
        viewModel.onRetry()
        advanceUntilIdle()

        assertThat(repository.searchQueries).isEmpty()
        assertThat(viewModel.isSearching.value).isFalse()
    }

    @Test
    fun `unavailable index surfaces actionable state`() = runTest {
        repository.ready = false

        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()

        assertThat(viewModel.searchUnavailable.value).isTrue()
        assertThat(viewModel.results.value).isEmpty()
    }

    @Test
    fun `current query reruns when background index initialization completes`() = runTest {
        repository.ready = false
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        assertThat(viewModel.searchUnavailable.value).isTrue()

        repository.stubbedResults = listOf(
            SearchResult("Adelaide", "city", null, -34.9285, 138.6007),
        )
        repository.ready = true
        advanceUntilIdle()

        assertThat(viewModel.searchUnavailable.value).isFalse()
        assertThat(viewModel.results.value.map { it.name }).containsExactly("Adelaide")
    }

    @Test
    fun `new query cancels a slower old query`() = runTest {
        repository.resultsByQuery["Old"] = listOf(
            SearchResult("Old result", "place", null, -34.0, 138.0),
        )
        repository.resultsByQuery["New"] = listOf(
            SearchResult("New result", "place", null, -35.0, 139.0),
        )
        repository.delayByQuery["Old"] = 1_000L

        viewModel.onQueryChange("Old")
        advanceTimeBy(301L)
        viewModel.onQueryChange("New")
        advanceUntilIdle()

        assertThat(viewModel.query.value).isEqualTo("New")
        assertThat(viewModel.results.value.map { it.name }).containsExactly("New result")
    }
}

private class FakeSearchRepository : SearchRepository {

    var stubbedResults: List<SearchResult> = emptyList()
    val resultsByQuery: MutableMap<String, List<SearchResult>> = mutableMapOf()
    val delayByQuery: MutableMap<String, Long> = mutableMapOf()
    var throwOnSearch: Boolean = false
    val searchQueries: MutableList<String> = mutableListOf()
    private val readinessState = MutableStateFlow(true)
    var ready: Boolean = true
        set(value) {
            field = value
            readinessState.value = value
        }

    override val isReady: Boolean
        get() = ready
    override val readiness: StateFlow<Boolean> = readinessState

    override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> {
        searchQueries += query
        if (throwOnSearch) throw RuntimeException("simulated index failure")
        delayByQuery[query]?.let { delay(it) }
        return resultsByQuery[query].orEmpty().ifEmpty { stubbedResults }.take(limit)
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
