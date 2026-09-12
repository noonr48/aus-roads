package au.com.ausroads.ui.map

import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.SpeedCameraPoint
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Viewport-driven camera layer coverage: the debounced viewport query lands in
 * state, the SQL radius is clamped to the sane band, and marker selection drives
 * the detail sheet state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CamerasViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: RecordingSearchRepository
    private lateinit var viewModel: CamerasViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = RecordingSearchRepository()
        viewModel = CamerasViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `viewport change loads cameras into state`() = runTest(testDispatcher.scheduler) {
        viewModel.onViewportChanged(
            centerLatitude = -34.9286,
            centerLongitude = 138.5999,
            halfSpanMeters = 3_000.0,
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.cameras).hasSize(2)
        assertThat(repository.lastMaxDistanceDegrees).isNotNull()
    }

    @Test
    fun `huge viewport is clamped to the ceiling radius`() = runTest(testDispatcher.scheduler) {
        // Half the state's latitude span — far beyond any sane query window.
        viewModel.onViewportChanged(
            centerLatitude = -30.0,
            centerLongitude = 135.0,
            halfSpanMeters = 1_500_000.0,
        )
        advanceUntilIdle()

        assertThat(repository.lastMaxDistanceDegrees!!).isAtMost(0.36)
    }

    @Test
    fun `tiny viewport is clamped to the floor radius`() = runTest(testDispatcher.scheduler) {
        viewModel.onViewportChanged(
            centerLatitude = -34.9,
            centerLongitude = 138.6,
            halfSpanMeters = 50.0,
        )
        advanceUntilIdle()

        assertThat(repository.lastMaxDistanceDegrees!!).isAtLeast(0.01)
    }

    @Test
    fun `select drives the detail sheet and clears back to null`() {
        val camera = SpeedCameraPoint(
            latitude = -34.9,
            longitude = 138.6,
            maxspeedKmh = 60,
            wayName = "Main North Rd",
        )

        viewModel.select(camera)
        assertThat(viewModel.state.value.selected).isEqualTo(camera)

        viewModel.select(null)
        assertThat(viewModel.state.value.selected).isNull()
    }

    /** Records camera-query arguments; every other repository op is inert. */
    private class RecordingSearchRepository : SearchRepository {
        var lastMaxDistanceDegrees: Double? = null
        var lastLimit: Int? = null

        override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> =
            emptyList()

        override suspend fun nearest(
            latitude: Double,
            longitude: Double,
            kind: String?,
            maxDistanceDegrees: Double,
        ): SearchResult? = null

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
        ): List<SpeedCameraPoint> {
            lastMaxDistanceDegrees = maxDistanceDegrees
            lastLimit = limit
            return listOf(
                SpeedCameraPoint(latitude = -34.9286, longitude = 138.5999, maxspeedKmh = 60, wayName = null),
                SpeedCameraPoint(latitude = -34.9290, longitude = 138.6001, maxspeedKmh = null, wayName = null),
            )
        }

        override fun open(dbPath: String) = Unit

        override fun close() = Unit
    }
}
