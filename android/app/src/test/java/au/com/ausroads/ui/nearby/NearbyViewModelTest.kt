package au.com.ausroads.ui.nearby

import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeSearchRepository
    private lateinit var viewModel: NearbyViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeSearchRepository()
        viewModel = NearbyViewModel(repository).apply {
            // Route the off-main work onto the test scheduler so it joins under
            // advanceUntilIdle().
            workDispatcher = testDispatcher
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `default reference is the Adelaide CBD`() = runTest(testDispatcher) {
        assertThat(viewModel.reference).isEqualTo(NearbyViewModel.ADELAIDE_CBD)
        assertThat(viewModel.reference.longitude).isEqualTo(138.6007)
        assertThat(viewModel.reference.latitude).isEqualTo(-34.9285)
    }

    @Test
    fun `initial ui state exposes all categories and no selection`() = runTest(testDispatcher) {
        val state = viewModel.uiState.value
        assertThat(state.categories).isEqualTo(PoiCategory.entries.toList())
        assertThat(state.selected).isNull()
        assertThat(state.results).isEmpty()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `selectCategory populates results sorted nearest-first and clears loading`() = runTest(testDispatcher) {
        // Supplied deliberately out of order: far, near, mid. Distances grow with
        // the longitude offset east of the CBD.
        repository.byCategory[PoiCategory.FUEL] = listOf(
            fuel("Far Servo", lonOffset = 0.30),
            fuel("Near Servo", lonOffset = 0.05),
            fuel("Mid Servo", lonOffset = 0.15),
        )

        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.selected).isEqualTo(PoiCategory.FUEL)
        assertThat(state.isLoading).isFalse()
        assertThat(state.results.map { it.result.name })
            .containsExactly("Near Servo", "Mid Servo", "Far Servo")
            .inOrder()
        // Distances are strictly ascending.
        val distances = state.results.map { it.distanceMeters }
        assertThat(distances).isInOrder()
        assertThat(distances.first()).isGreaterThan(0.0)
    }

    @Test
    fun `selectCategory with no matches yields empty results and not loading`() = runTest(testDispatcher) {
        viewModel.selectCategory(PoiCategory.TOILETS)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.selected).isEqualTo(PoiCategory.TOILETS)
        assertThat(state.results).isEmpty()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `selectCategory swallows repository failure and ends not loading`() = runTest(testDispatcher) {
        repository.throwOnNearest = true

        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.results).isEmpty()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `emergency info is loaded from init`() = runTest(testDispatcher) {
        repository.byCategory[PoiCategory.HOSPITAL] =
            listOf(fuel("Royal Adelaide Hospital", lonOffset = 0.02))
        repository.byCategory[PoiCategory.POLICE] =
            listOf(fuel("Adelaide Police Station", lonOffset = 0.03))

        // init already launched loadEmergency(); drain it.
        viewModel = NearbyViewModel(repository).apply { workDispatcher = testDispatcher }
        advanceUntilIdle()

        val emergency = viewModel.uiState.value.emergency
        assertThat(emergency).isNotNull()
        assertThat(emergency!!.hospital?.result?.name).isEqualTo("Royal Adelaide Hospital")
        assertThat(emergency.police?.result?.name).isEqualTo("Adelaide Police Station")
        assertThat(emergency.hospital!!.distanceMeters).isGreaterThan(0.0)
    }

    @Test
    fun `setReference moves the reference and clears results`() = runTest(testDispatcher) {
        repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Some Servo", lonOffset = 0.05))
        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.results).isNotEmpty()

        viewModel.setReference(latitude = -33.8688, longitude = 151.2093)
        advanceUntilIdle()

        assertThat(viewModel.reference.latitude).isEqualTo(-33.8688)
        assertThat(viewModel.reference.longitude).isEqualTo(151.2093)
        assertThat(viewModel.uiState.value.selected).isNull()
        assertThat(viewModel.uiState.value.results).isEmpty()
    }

    @Test
    fun `a slow earlier selection cannot overwrite a newer selection's results`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Servo", lonOffset = 0.05))
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("Hospital", lonOffset = 0.02))
            // Make the first (FUEL) query land long AFTER the second (HOSPITAL) one.
            repository.delayByCategory[PoiCategory.FUEL] = 1_000L
            repository.delayByCategory[PoiCategory.HOSPITAL] = 10L

            viewModel.selectCategory(PoiCategory.FUEL)
            viewModel.selectCategory(PoiCategory.HOSPITAL)
            advanceUntilIdle()

            // Latest-wins: the stale FUEL result must not clobber HOSPITAL.
            val state = viewModel.uiState.value
            assertThat(state.selected).isEqualTo(PoiCategory.HOSPITAL)
            assertThat(state.results.map { it.result.name }).containsExactly("Hospital")
            assertThat(state.isLoading).isFalse()
        }

    /** A POI east of the Adelaide CBD by [lonOffset] degrees. */
    private fun fuel(name: String, lonOffset: Double): SearchResult =
        SearchResult(
            name = name,
            kind = "poi",
            className = "amenity=fuel",
            latitude = -34.9285,
            longitude = 138.6007 + lonOffset,
        )
}

/**
 * Minimal in-memory [SearchRepository] for the Nearby tests. Only
 * [nearestByCategory] is exercised; everything else is a no-op/empty stub.
 */
private class FakeSearchRepository : SearchRepository {

    val byCategory: MutableMap<PoiCategory, List<SearchResult>> = mutableMapOf()
    var throwOnNearest: Boolean = false

    /** Per-category artificial latency (virtual ms) so tests can force a slow
     *  earlier query to land after a faster later one. */
    val delayByCategory: MutableMap<PoiCategory, Long> = mutableMapOf()

    override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> =
        emptyList()

    override suspend fun nearest(
        latitude: Double,
        longitude: Double,
        kind: String?,
        maxDistanceDegrees: Double,
    ): SearchResult? = null

    override suspend fun browseByCategory(category: PoiCategory, limit: Int): List<SearchResult> =
        byCategory[category].orEmpty().take(limit)

    override suspend fun nearestByCategory(
        latitude: Double,
        longitude: Double,
        category: PoiCategory,
        limit: Int,
        maxDistanceDegrees: Double,
    ): List<SearchResult> {
        if (throwOnNearest) throw RuntimeException("simulated index failure")
        delayByCategory[category]?.let { delay(it) }
        return byCategory[category].orEmpty().take(limit)
    }

    override suspend fun maxspeedNear(
        latitude: Double,
        longitude: Double,
        maxDistanceDegrees: Double,
    ): Int? = null

    override fun open(dbPath: String) {}

    override fun close() {}
}
