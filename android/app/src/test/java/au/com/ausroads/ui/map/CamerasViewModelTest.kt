package au.com.ausroads.ui.map

import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.DiscoveryRequest
import au.com.ausroads.offline.search.PlaceDiscoveryRepository
import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.SpeedCameraPoint
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Viewport-driven camera layer coverage: the debounced viewport query lands in state, the SQL
 * radius is clamped to the sane band, and marker selection drives the detail sheet state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CamerasViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: RecordingSearchRepository
    private lateinit var viewModel: CamerasViewModel
    private lateinit var health: PlacesHealth

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = RecordingSearchRepository()
        health = mockk(relaxed = true)
        viewModel = CamerasViewModel(repository, health)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `viewport change loads cameras into state`() =
        runTest(testDispatcher.scheduler) {
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
    fun `late catalog readiness refreshes initial viewport without a pan`() =
        runTest(testDispatcher.scheduler) {
            repository.availability.value = DiscoveryAvailability.LOADING
            viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0)
            advanceUntilIdle()
            assertThat(viewModel.state.value.cameras).isEmpty()
            repository.availability.value = DiscoveryAvailability.READY
            repository.generation.value += 1
            advanceUntilIdle()
            assertThat(viewModel.state.value.cameras).hasSize(2)
        }

    @Test
    fun `ready catalog replacement refreshes unchanged viewport`() =
        runTest(testDispatcher.scheduler) {
            viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0)
            advanceUntilIdle()
            val replacement = SpeedCameraPoint(-34.93, 138.61, 40, "Replacement")
            repository.cameras = listOf(replacement)
            repository.generation.value += 1
            advanceUntilIdle()
            assertThat(viewModel.state.value.cameras).containsExactly(replacement)
        }

    @Test
    fun `huge viewport is clamped to the ceiling radius`() =
        runTest(testDispatcher.scheduler) {
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
    fun `tiny viewport is clamped to the floor radius`() =
        runTest(testDispatcher.scheduler) {
            viewModel.onViewportChanged(
                centerLatitude = -34.9,
                centerLongitude = 138.6,
                halfSpanMeters = 50.0,
            )
            advanceUntilIdle()

            assertThat(repository.lastMaxDistanceDegrees!!).isAtLeast(0.01)
        }

    @Test
    fun `camera query kill produces persistent seam witness then generation retry succeeds`() =
        runTest(testDispatcher.scheduler) {
            repository.failCameraQuery = true
            viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0)
            advanceUntilIdle()
            assertThat(viewModel.state.value.cameras).isEmpty()
            verify { health.failure(PlacesSeam.CAMERAS_QUERY) }
            repository.failCameraQuery = false
            repository.generation.value += 1
            advanceUntilIdle()
            assertThat(viewModel.state.value.cameras).hasSize(2)
            verify { health.success(PlacesSeam.CAMERAS_QUERY) }
        }

    @Test fun `failed identical viewport retries without changing catalog readiness or generation`() =
        runTest(testDispatcher.scheduler) {
            repository.failCameraQuery = true
            viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0)
            advanceUntilIdle()
            assertThat(repository.cameraQueries).isEqualTo(1)
            assertThat(viewModel.state.value.queryFailed).isTrue()
            val generation = repository.generation.value
            repository.failCameraQuery = false
            viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0)
            advanceUntilIdle()
            assertThat(repository.cameraQueries).isEqualTo(2)
            assertThat(viewModel.state.value.cameras).hasSize(2)
            assertThat(viewModel.state.value.queryFailed).isFalse()
            assertThat(repository.generation.value).isEqualTo(generation)
            assertThat(repository.availability.value).isEqualTo(DiscoveryAvailability.READY)
            verify(exactly = 1) { health.failure(PlacesSeam.CAMERAS_QUERY) }
            verify(exactly = 1) { health.success(PlacesSeam.CAMERAS_QUERY) }
        }

    @Test fun `healthy identical viewport remains deduplicated`() =
        runTest(testDispatcher.scheduler) {
            viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0)
            advanceUntilIdle()
            viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0)
            advanceUntilIdle()
            assertThat(repository.cameraQueries).isEqualTo(1)
        }

    @Test fun `one failed viewport retry consumes repeated idle callbacks but retains later failure`() =
        runTest(testDispatcher.scheduler) {
            repository.failCameraQuery = true
            viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0)
            advanceUntilIdle()
            repeat(3) { viewModel.onViewportChanged(-34.9286, 138.5999, 3_000.0) }
            advanceUntilIdle()
            assertThat(repository.cameraQueries).isEqualTo(2)
            assertThat(viewModel.state.value.cameras).isEmpty()
            assertThat(viewModel.state.value.queryFailed).isTrue()
            verify(exactly = 2) { health.failure(PlacesSeam.CAMERAS_QUERY) }
            verify(exactly = 0) { health.success(PlacesSeam.CAMERAS_QUERY) }
        }

    @Test
    fun `select drives the detail sheet and clears back to null`() {
        val camera =
            SpeedCameraPoint(
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
    private class RecordingSearchRepository : SearchRepository, PlaceDiscoveryRepository {
        var lastMaxDistanceDegrees: Double? = null
        var lastLimit: Int? = null
        var failCameraQuery = false
        var cameraQueries = 0
        override val availability = MutableStateFlow(DiscoveryAvailability.READY)
        override val generation = MutableStateFlow(0L)
        var cameras =
            listOf(
                SpeedCameraPoint(-34.9286, 138.5999, 60, null),
                SpeedCameraPoint(-34.9290, 138.6001, null, null),
            )

        override suspend fun discover(request: DiscoveryRequest): List<SearchResult> = emptyList()

        override suspend fun search(query: String, limit: Int, kind: String?): List<SearchResult> =
            emptyList()

        override suspend fun nearest(
            latitude: Double,
            longitude: Double,
            kind: String?,
            maxDistanceDegrees: Double,
        ): SearchResult? = null

        override suspend fun browseByCategory(
            category: PoiCategory,
            limit: Int,
        ): List<SearchResult> = emptyList()

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
            cameraQueries++
            if (failCameraQuery) throw IOException("fixture camera query")
            lastMaxDistanceDegrees = maxDistanceDegrees
            lastLimit = limit
            return if (availability.value == DiscoveryAvailability.READY) cameras else emptyList()
        }

        override fun open(dbPath: String) = Unit

        override fun close() = Unit
    }
}
