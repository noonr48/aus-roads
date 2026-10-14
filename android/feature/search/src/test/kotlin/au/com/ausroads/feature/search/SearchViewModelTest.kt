package au.com.ausroads.feature.search

import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.DiscoveryRequest
import au.com.ausroads.offline.search.PlaceDiscoveryRepository
import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.SpeedCameraPoint
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeSearchRepository
    private lateinit var viewModel: SearchViewModel
    private lateinit var health: RecordingSearchHealth

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeSearchRepository()
        health = RecordingSearchHealth()
        viewModel = SearchViewModel(repository, health)
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
        repository.stubbedResults = listOf(SearchResult("Melbourne", "city", null, -37.81, 144.96))
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

    @Test
    fun `explicit retry repeats failed exact query and records both outcomes without catalog change`() =
        runTest {
            repository.throwOnSearch = true
            viewModel.onQueryChange("Adelaide")
            advanceUntilIdle()
            assertThat(health.failures).isEqualTo(1)
            repository.throwOnSearch = false
            repository.stubbedResults =
                listOf(SearchResult("Recovered", "poi", null, -34.92, 138.62))
            viewModel.onQueryChange("Adelaide")
            advanceUntilIdle()
            assertThat(viewModel.searchFailed.value).isTrue()
            viewModel.retry()
            advanceUntilIdle()
            assertThat(viewModel.query.value).isEqualTo("Adelaide")
            assertThat(repository.generation.value).isEqualTo(0L)
            assertThat(viewModel.searchFailed.value).isFalse()
            assertThat(viewModel.isSearching.value).isFalse()
            assertThat(viewModel.results.value.single().name).isEqualTo("Recovered")
            assertThat(health.failures).isEqualTo(1)
            assertThat(health.successes).isEqualTo(1)
        }

    @Test
    fun `ready catalog replacement refreshes an unchanged query`() = runTest {
        repository.throwOnSearch = true
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        assertThat(viewModel.searchFailed.value).isTrue()
        repository.throwOnSearch = false
        repository.stubbedResults = listOf(SearchResult("New catalog", "poi", null, -34.92, 138.62))
        repository.generation.value += 1
        advanceUntilIdle()
        assertThat(viewModel.searchFailed.value).isFalse()
        assertThat(viewModel.results.value.single().name).isEqualTo("New catalog")
    }

    @Test
    fun `completed query edited away and back within debounce runs again`() = runTest {
        repository.stubbedResults = listOf(SearchResult("OLD", "city", null, -34.92, 138.62))
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        assertThat(viewModel.results.value).hasSize(1)
        viewModel.onQueryChange("AdelaideX")
        runCurrent()
        advanceTimeBy(100)
        repository.stubbedResults = listOf(SearchResult("RETURNED", "city", null, -34.92, 138.62))
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        assertThat(viewModel.isSearching.value).isFalse()
        assertThat(viewModel.results.value.single().name).isEqualTo("RETURNED")
    }

    @Test
    fun `conflated same-turn A to B to A edits still complete`() = runTest {
        repository.stubbedResults = listOf(SearchResult("OLD", "city", null, -34.92, 138.62))
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        repository.stubbedResults = listOf(SearchResult("RETURNED", "city", null, -34.92, 138.62))
        viewModel.onQueryChange("AdelaideX")
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        assertThat(viewModel.isSearching.value).isFalse()
        assertThat(viewModel.results.value.single().name).isEqualTo("RETURNED")
    }

    @Test
    fun `identical query event preserves completed results`() = runTest {
        repository.stubbedResults = listOf(SearchResult("Adelaide", "city", null, -34.92, 138.62))
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        viewModel.onQueryChange("Adelaide")
        advanceUntilIdle()
        assertThat(viewModel.isSearching.value).isFalse()
        assertThat(viewModel.results.value).hasSize(1)
    }

    @Test
    fun `old query cannot publish inside the new query debounce window`() = runTest {
        repository.stubbedResults = listOf(SearchResult("OLD", "poi", null, -34.92, 138.62))
        repository.delayMs = 1_000
        viewModel.onQueryChange("old")
        advanceTimeBy(301)
        runCurrent()
        advanceTimeBy(989)
        viewModel.onQueryChange("new")
        advanceTimeBy(20)
        runCurrent()
        assertThat(viewModel.query.value).isEqualTo("new")
        assertThat(viewModel.results.value).isEmpty()
        repository.delayMs = 0
        repository.stubbedResults = listOf(SearchResult("NEW", "poi", null, -34.92, 138.62))
        advanceUntilIdle()
        assertThat(viewModel.results.value.single().name).isEqualTo("NEW")
    }
}

private class RecordingSearchHealth : SearchHealth {
    var failures = 0
    var successes = 0

    override fun success() {
        successes++
    }

    override fun failure() {
        failures++
    }
}

private class FakeSearchRepository : SearchRepository, PlaceDiscoveryRepository {

    var stubbedResults: List<SearchResult> = emptyList()
    var throwOnSearch: Boolean = false
    var delayMs: Long = 0
    override val availability = MutableStateFlow(DiscoveryAvailability.READY)
    override val generation = MutableStateFlow(0L)

    override suspend fun discover(request: DiscoveryRequest): List<SearchResult> = emptyList()

    override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> {
        if (throwOnSearch) throw RuntimeException("simulated index failure")
        val snapshot = stubbedResults.take(limit)
        delay(delayMs)
        return snapshot
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

    override suspend fun camerasNear(
        latitude: Double,
        longitude: Double,
        maxDistanceDegrees: Double,
        limit: Int,
    ): List<SpeedCameraPoint> = emptyList()

    override fun open(dbPath: String) {}

    override fun close() {}
}
