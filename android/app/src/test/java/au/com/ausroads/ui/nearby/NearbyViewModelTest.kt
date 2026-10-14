package au.com.ausroads.ui.nearby

import androidx.lifecycle.ViewModelStore
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.di.SearchInitializer
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.DiscoveryRequest
import au.com.ausroads.offline.search.PlaceDiscoveryRepository
import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.SpeedCameraPoint
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
class NearbyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val health = mockk<PlacesHealth>(relaxed = true)
    private lateinit var repository: FakeSearchRepository
    private lateinit var viewModel: NearbyViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeSearchRepository()
        viewModel =
            NearbyViewModel(repository, health).apply {
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
    fun `default reference is the Adelaide CBD`() =
        runTest(testDispatcher) {
            assertThat(viewModel.reference).isEqualTo(NearbyViewModel.ADELAIDE_CBD)
            assertThat(viewModel.reference.longitude).isEqualTo(138.6007)
            assertThat(viewModel.reference.latitude).isEqualTo(-34.9285)
        }

    @Test
    fun `initial ui state exposes all categories and no selection`() =
        runTest(testDispatcher) {
            val state = viewModel.uiState.value
            assertThat(state.categories).isEqualTo(PoiCategory.entries.toList())
            assertThat(state.selected).isNull()
            assertThat(state.results).isEmpty()
            assertThat(state.isLoading).isFalse()
        }

    @Test
    fun `selectCategory populates results sorted nearest-first and clears loading`() =
        runTest(testDispatcher) {
            // Supplied deliberately out of order: far, near, mid. Distances grow with
            // the longitude offset east of the CBD.
            repository.byCategory[PoiCategory.FUEL] =
                listOf(
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
    fun `selectCategory with no matches yields empty results and not loading`() =
        runTest(testDispatcher) {
            viewModel.selectCategory(PoiCategory.TOILETS)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.selected).isEqualTo(PoiCategory.TOILETS)
            assertThat(state.results).isEmpty()
            assertThat(state.isLoading).isFalse()
        }

    @Test
    fun `selectCategory swallows repository failure and ends not loading`() =
        runTest(testDispatcher) {
            repository.throwOnNearest = true

            viewModel.selectCategory(PoiCategory.FUEL)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.results).isEmpty()
            assertThat(state.isLoading).isFalse()
            assertThat(state.categoryLookupFailed).isTrue()
        }

    @Test
    fun `emergency info is loaded from init`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.HOSPITAL] =
                listOf(fuel("Royal Adelaide Hospital", lonOffset = 0.02))
            repository.byCategory[PoiCategory.POLICE] =
                listOf(fuel("Adelaide Police Station", lonOffset = 0.03))

            // init already launched loadEmergency(); drain it.
            viewModel = NearbyViewModel(repository, health).apply { workDispatcher = testDispatcher }
            advanceUntilIdle()

            val emergency = viewModel.uiState.value.emergency
            assertThat(emergency).isNotNull()
            assertThat(emergency!!.hospital?.result?.name).isEqualTo("Royal Adelaide Hospital")
            assertThat(emergency.police?.result?.name).isEqualTo("Adelaide Police Station")
            assertThat(emergency.hospital!!.distanceMeters).isGreaterThan(0.0)
        }

    @Test
    fun `setReference moves the reference and refreshes the preserved category`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Some Servo", lonOffset = 0.05))
            viewModel.selectCategory(PoiCategory.FUEL)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.results).isNotEmpty()

            viewModel.setReference(latitude = -33.8688, longitude = 151.2093)
            advanceUntilIdle()

            assertThat(viewModel.reference.latitude).isEqualTo(-33.8688)
            assertThat(viewModel.reference.longitude).isEqualTo(151.2093)
            assertThat(viewModel.uiState.value.selected).isEqualTo(PoiCategory.FUEL)
            assertThat(viewModel.uiState.value.results.single().distanceMeters)
                .isGreaterThan(100_000.0)
        }

    @Test
    fun `onDeviceLocation adopts the first fix and flags device reference`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Servo", lonOffset = 0.05))
            viewModel.selectCategory(PoiCategory.FUEL)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.results).isNotEmpty()

            viewModel.onDeviceLocation(latitude = -34.95, longitude = 138.65)
            advanceUntilIdle()

            assertThat(viewModel.reference.latitude).isEqualTo(-34.95)
            assertThat(viewModel.reference.longitude).isEqualTo(138.65)
            assertThat(viewModel.uiState.value.usingDeviceLocation).isTrue()
            // The selected category survives; distances are recomputed against the new fix.
            assertThat(viewModel.uiState.value.selected).isEqualTo(PoiCategory.FUEL)
            assertThat(viewModel.uiState.value.results).isNotEmpty()
        }

    @Test
    fun `streaming device fixes within the move threshold are ignored`() =
        runTest(testDispatcher) {
            viewModel.onDeviceLocation(latitude = -34.95, longitude = 138.65)
            advanceUntilIdle()
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Servo", lonOffset = 0.05))
            viewModel.selectCategory(PoiCategory.FUEL)
            advanceUntilIdle()
            val loaded = viewModel.uiState.value
            assertThat(loaded.results).isNotEmpty()

            // ~6 m nudge: far below MIN_MOVE_METERS — must not re-center or clear.
            viewModel.onDeviceLocation(latitude = -34.94996, longitude = 138.65004)
            advanceUntilIdle()

            assertThat(viewModel.reference.latitude).isEqualTo(-34.95)
            assertThat(viewModel.reference.longitude).isEqualTo(138.65)
            assertThat(viewModel.uiState.value.results.map { it.result.name })
                .containsExactly("Servo")

            // A real move refreshes the same category rather than throwing away the task.
            viewModel.onDeviceLocation(latitude = -34.97, longitude = 138.70)
            advanceUntilIdle()

            assertThat(viewModel.reference.latitude).isEqualTo(-34.97)
            assertThat(viewModel.uiState.value.selected).isEqualTo(PoiCategory.FUEL)
            assertThat(viewModel.uiState.value.results).isNotEmpty()
        }

    @Test
    fun `manual setReference overrides a device reference and clears its flag`() =
        runTest(testDispatcher) {
            viewModel.onDeviceLocation(latitude = -34.95, longitude = 138.65)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.usingDeviceLocation).isTrue()

            viewModel.setReference(latitude = -33.8688, longitude = 151.2093)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.usingDeviceLocation).isFalse()
            assertThat(viewModel.reference.latitude).isEqualTo(-33.8688)
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

    @Test
    fun `emergency lookup failure surfaces an explicit error flag`() =
        runTest(testDispatcher) {
            repository.throwOnNearest = true
            viewModel = NearbyViewModel(repository, health).apply { workDispatcher = testDispatcher }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.emergencyLookupFailed).isTrue()
            assertThat(state.emergency?.hospital).isNull()
            assertThat(state.emergency?.police).isNull()
        }

    @Test
    fun `slow older emergency lookup cannot overwrite newer reference state`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.HOSPITAL] =
                listOf(fuel("Royal Adelaide Hospital", lonOffset = 0.02))
            repository.byCategory[PoiCategory.POLICE] =
                listOf(fuel("Adelaide Police Station", lonOffset = 0.03))
            // The init-launched emergency lookup's hospital query is slow.
            repository.delayByCategory[PoiCategory.HOSPITAL] = 1_000L

            viewModel = NearbyViewModel(repository, health).apply { workDispatcher = testDispatcher }
            runCurrent() // lookup #1 suspended inside its slow hospital delay

            // A reference move supersedes it: token bumps, lookup #2 launches with
            // a fast hospital query that finds nothing at the new reference.
            repository.byCategory[PoiCategory.HOSPITAL] = emptyList()
            repository.delayByCategory[PoiCategory.HOSPITAL] = 10L
            viewModel.setReference(latitude = -33.8688, longitude = 151.2093)

            advanceTimeBy(100)
            assertThat(viewModel.uiState.value.emergency?.hospital).isNull()

            advanceTimeBy(1_000)
            // The stale lookup must not publish over the fresher emergency state.
            assertThat(viewModel.uiState.value.emergency?.hospital).isNull()
        }

    @Test
    fun `successful emergency load leaves no failure flag`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.HOSPITAL] =
                listOf(fuel("Royal Adelaide Hospital", lonOffset = 0.02))
            repository.byCategory[PoiCategory.POLICE] =
                listOf(fuel("Adelaide Police Station", lonOffset = 0.03))
            viewModel = NearbyViewModel(repository, health).apply { workDispatcher = testDispatcher }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.emergencyLookupFailed).isFalse()
            assertThat(state.emergency?.hospital?.result?.name).isEqualTo("Royal Adelaide Hospital")
        }

    @Test
    fun `empty emergency results without failure keep the flag down`() =
        runTest(testDispatcher) {
            // No POIs seeded at all: none-found, not a lookup error.
            viewModel = NearbyViewModel(repository, health).apply { workDispatcher = testDispatcher }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.emergencyLookupFailed).isFalse()
            assertThat(state.emergency?.hospital).isNull()
            assertThat(state.emergency?.police).isNull()
        }

    @Test
    fun `category selection does not invalidate initial emergency lookup`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("Hospital", 0.02))
            repository.delayByCategory[PoiCategory.HOSPITAL] = 1_000L
            runCurrent()
            viewModel.selectCategory(PoiCategory.FUEL)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.emergency?.hospital?.result?.name)
                .isEqualTo("Hospital")
        }

    @Test
    fun `fixed reference catalog replacement refreshes emergency and category data`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("Old hospital", 0.02))
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Old fuel", 0.03))
            viewModel.selectCategory(PoiCategory.FUEL)
            advanceUntilIdle()
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("New hospital", 0.02))
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("New fuel", 0.03))
            repository.generation.value += 1
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.emergency?.hospital?.result?.name)
                .isEqualTo("New hospital")
            assertThat(viewModel.uiState.value.results.single().result.name).isEqualTo("New fuel")
        }

    @Test
    fun `loading catalog immediately removes completed old emergency and category data`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("Old hospital", 0.02))
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Old fuel", 0.03))
            viewModel.selectCategory(PoiCategory.FUEL)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.emergency?.hospital).isNotNull()
            assertThat(viewModel.uiState.value.results).isNotEmpty()

            repository.availability.value = DiscoveryAvailability.LOADING
            runCurrent()

            val state = viewModel.uiState.value
            assertThat(state.selected).isEqualTo(PoiCategory.FUEL)
            assertThat(state.emergency).isNull()
            assertThat(state.results).isEmpty()
            assertThat(state.isLoading).isTrue()
            assertThat(state.emergencyLookupFailed).isFalse()
            assertThat(state.categoryLookupFailed).isFalse()
        }

    @Test
    fun `pending old emergency cannot publish while catalog is loading`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("Old hospital", 0.02))
            repository.snapshotBeforeDelay = true
            repository.delayByCategory[PoiCategory.HOSPITAL] = 1_000L
            runCurrent()
            repository.availability.value = DiscoveryAvailability.LOADING
            runCurrent()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.emergency).isNull()
            assertThat(viewModel.uiState.value.emergencyLookupFailed).isFalse()
        }

    @Test
    fun `pending old category cannot publish or finish loading across catalog withdrawal`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Old fuel", 0.03))
            repository.snapshotBeforeDelay = true
            repository.delayByCategory[PoiCategory.FUEL] = 1_000L
            viewModel.selectCategory(PoiCategory.FUEL)
            runCurrent()
            repository.availability.value = DiscoveryAvailability.LOADING
            runCurrent()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.results).isEmpty()
            assertThat(viewModel.uiState.value.isLoading).isTrue()
            assertThat(viewModel.uiState.value.categoryLookupFailed).isFalse()
        }

    @Test
    fun `terminal catalog failure is not an empty lookup and ready epoch recovers selection`() =
        runTest(testDispatcher) {
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("Old hospital", 0.02))
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Old fuel", 0.03))
            viewModel.selectCategory(PoiCategory.FUEL)
            advanceUntilIdle()
            repository.availability.value = DiscoveryAvailability.LOADING
            runCurrent()
            val calls = repository.nearestCalls.size
            repository.availability.value = DiscoveryAvailability.FAILED
            advanceUntilIdle()
            val failed = viewModel.uiState.value
            assertThat(failed.results).isEmpty()
            assertThat(failed.emergency).isNull()
            assertThat(failed.isLoading).isFalse()
            assertThat(failed.categoryLookupFailed).isTrue()
            assertThat(failed.emergencyLookupFailed).isTrue()
            assertThat(repository.nearestCalls).hasSize(calls)

            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("New hospital", 0.02))
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("New fuel", 0.03))
            repository.generation.value += 1
            repository.availability.value = DiscoveryAvailability.READY
            advanceUntilIdle()
            val recovered = viewModel.uiState.value
            assertThat(recovered.selected).isEqualTo(PoiCategory.FUEL)
            assertThat(recovered.results.single().result.name).isEqualTo("New fuel")
            assertThat(recovered.emergency?.hospital?.result?.name).isEqualTo("New hospital")
            assertThat(recovered.categoryLookupFailed).isFalse()
            assertThat(recovered.emergencyLookupFailed).isFalse()
        }

    @Test
    fun `initial loading catalog does not publish emergency data or query failure`() =
        runTest(testDispatcher) {
            repository.availability.value = DiscoveryAvailability.LOADING
            repository.throwOnNearest = true
            viewModel = NearbyViewModel(repository, health).apply { workDispatcher = testDispatcher }
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.emergency).isNull()
            assertThat(viewModel.uiState.value.emergencyLookupFailed).isFalse()
            assertThat(repository.nearestCalls).isEmpty()
        }

    @Test fun `share dispatch refusal and recovery record only the fixed seam`() =
        runTest(testDispatcher) {
            advanceUntilIdle()
            val before = viewModel.uiState.value
            viewModel.recordShareOutcome(false)
            verify(exactly = 1) { health.failure(PlacesSeam.LOCATION_SHARE) }
            verify(exactly = 0) { health.success(PlacesSeam.LOCATION_SHARE) }
            viewModel.recordShareOutcome(true)
            verify(exactly = 1) { health.success(PlacesSeam.LOCATION_SHARE) }
            assertThat(viewModel.uiState.value).isEqualTo(before)
        }

    @Test
    fun `category Retry initializes failed catalog and restores the same category through READY`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        recoveringModel(initializer, DiscoveryAvailability.FAILED)
        every { initializer.initialize() } answers {
            repository.availability.value = DiscoveryAvailability.LOADING
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Recovered fuel", 0.03))
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("Recovered hospital", 0.02))
            repository.generation.value += 1
            repository.availability.value = DiscoveryAvailability.READY
        }
        advanceUntilIdle()
        verify(exactly = 0) { initializer.initialize() }
        assertThat(viewModel.uiState.value.emergencyLookupFailed).isTrue()
        viewModel.selectCategory(PoiCategory.FUEL) // Same action used by the existing category Retry.
        advanceUntilIdle()
        verify(exactly = 1) { initializer.initialize() }
        assertThat(viewModel.uiState.value.selected).isEqualTo(PoiCategory.FUEL)
        assertThat(viewModel.uiState.value.results.single().result.name).isEqualTo("Recovered fuel")
        assertThat(viewModel.uiState.value.emergency?.hospital?.result?.name).isEqualTo("Recovered hospital")
        assertThat(viewModel.uiState.value.categoryLookupFailed).isFalse()
        assertThat(viewModel.uiState.value.emergencyLookupFailed).isFalse()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun `emergency Retry initializes unavailable catalog and refreshes preserved category`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        recoveringModel(initializer, DiscoveryAvailability.READY)
        repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Old fuel", 0.03))
        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()
        repository.availability.value = DiscoveryAvailability.UNAVAILABLE
        advanceUntilIdle()
        every { initializer.initialize() } answers {
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("New fuel", 0.04))
            repository.byCategory[PoiCategory.HOSPITAL] = listOf(fuel("New hospital", 0.02))
            repository.generation.value += 1
            repository.availability.value = DiscoveryAvailability.READY
        }
        viewModel.loadEmergency() // Same action used by the existing emergency Retry.
        advanceUntilIdle()
        verify(exactly = 1) { initializer.initialize() }
        assertThat(viewModel.uiState.value.selected).isEqualTo(PoiCategory.FUEL)
        assertThat(viewModel.uiState.value.results.single().result.name).isEqualTo("New fuel")
        assertThat(viewModel.uiState.value.emergency?.hospital?.result?.name).isEqualTo("New hospital")
        assertThat(viewModel.uiState.value.emergencyLookupFailed).isFalse()
        assertThat(viewModel.uiState.value.categoryLookupFailed).isFalse()
    }

    @Test
    fun `active recovery coalesces both Retry actions and uses latest category and reference`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        recoveringModel(initializer, DiscoveryAvailability.FAILED)
        val held = HeldCatalogDispatcher()
        viewModel.catalogDispatcher = held
        repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Old selection", 0.03))
        repository.byCategory[PoiCategory.TOILETS] = listOf(fuel("Current selection", 0.04))
        every { initializer.initialize() } answers {
            repository.generation.value += 1
            repository.availability.value = DiscoveryAvailability.READY
        }
        viewModel.selectCategory(PoiCategory.FUEL)
        runCurrent() // Bootstrap is queued on its separate dispatcher, not executed on Main.
        verify(exactly = 0) { initializer.initialize() }
        viewModel.loadEmergency()
        viewModel.loadEmergency()
        viewModel.selectCategory(PoiCategory.TOILETS)
        viewModel.setReference(-35.0, 139.0)
        runCurrent()
        assertThat(repository.nearestCalls).isEmpty()
        held.runPending()
        advanceUntilIdle()
        verify(exactly = 1) { initializer.initialize() }
        assertThat(viewModel.uiState.value.selected).isEqualTo(PoiCategory.TOILETS)
        assertThat(viewModel.uiState.value.results.single().result.name).isEqualTo("Current selection")
        assertThat(repository.nearestCalls).doesNotContain(PoiCategory.FUEL)
        assertThat(repository.nearestOrigins).isNotEmpty()
        assertThat(repository.nearestOrigins.toSet()).containsExactly(-35.0 to 139.0)
    }

    @Test
    fun `terminal bootstrap result remains failed without an automatic Retry loop`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        every { initializer.initialize() } returns Unit // A caught bootstrap fault leaves the catalog FAILED.
        recoveringModel(initializer, DiscoveryAvailability.FAILED)
        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()
        advanceUntilIdle()
        verify(exactly = 1) { initializer.initialize() }
        assertThat(repository.nearestCalls).isEmpty()
        assertThat(viewModel.uiState.value.results).isEmpty()
        assertThat(viewModel.uiState.value.emergency).isNull()
        assertThat(viewModel.uiState.value.categoryLookupFailed).isTrue()
        assertThat(viewModel.uiState.value.emergencyLookupFailed).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        viewModel.loadEmergency()
        advanceUntilIdle()
        verify(exactly = 2) { initializer.initialize() } // Only a new explicit action retries.
    }

    @Test
    fun `unexpected initializer failure has fixed witness and next explicit Retry can recover`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        every { initializer.initialize() } throws IOException("fixture bootstrap")
        recoveringModel(initializer, DiscoveryAvailability.FAILED)
        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()
        verify(exactly = 1) { health.failure(PlacesSeam.CATALOG_BOOTSTRAP) }
        assertThat(viewModel.uiState.value.categoryLookupFailed).isTrue()
        assertThat(viewModel.uiState.value.emergencyLookupFailed).isTrue()
        assertThat(repository.nearestCalls).isEmpty()
        every { initializer.initialize() } answers {
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Recovered", 0.03))
            repository.generation.value += 1
            repository.availability.value = DiscoveryAvailability.READY
        }
        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()
        verify(exactly = 2) { initializer.initialize() }
        assertThat(viewModel.uiState.value.results.single().result.name).isEqualTo("Recovered")
        assertThat(viewModel.uiState.value.categoryLookupFailed).isFalse()
    }

    @Test
    fun `loading catalog and passive reference changes never initialize catalog`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        recoveringModel(initializer, DiscoveryAvailability.FAILED)
        advanceUntilIdle()
        viewModel.setReference(-35.0, 139.0)
        advanceUntilIdle()
        repository.availability.value = DiscoveryAvailability.LOADING
        viewModel.selectCategory(PoiCategory.FUEL)
        viewModel.loadEmergency()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isLoading).isTrue()
        assertThat(viewModel.uiState.value.categoryLookupFailed).isFalse()
        repository.availability.value = DiscoveryAvailability.FAILED
        advanceUntilIdle()
        verify(exactly = 0) { initializer.initialize() }
        assertThat(repository.nearestCalls).isEmpty()
    }

    @Test
    fun `READY query Retry uses current epoch without bootstrap and records fixed query outcomes`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        recoveringModel(initializer, DiscoveryAvailability.READY)
        advanceUntilIdle()
        val epoch = repository.generation.value
        repository.throwOnNearest = true
        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()
        viewModel.loadEmergency()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.categoryLookupFailed).isTrue()
        assertThat(viewModel.uiState.value.emergencyLookupFailed).isTrue()
        verify(exactly = 2) { health.failure(PlacesSeam.CATALOG_QUERY) }
        repository.throwOnNearest = false
        repository.byCategory[PoiCategory.FUEL] = listOf(fuel("Repaired query", 0.03))
        viewModel.selectCategory(PoiCategory.FUEL)
        viewModel.loadEmergency()
        advanceUntilIdle()
        verify(exactly = 0) { initializer.initialize() }
        assertThat(repository.generation.value).isEqualTo(epoch)
        assertThat(viewModel.uiState.value.results.single().result.name).isEqualTo("Repaired query")
        assertThat(viewModel.uiState.value.categoryLookupFailed).isFalse()
        assertThat(viewModel.uiState.value.emergencyLookupFailed).isFalse()
        verify { health.success(PlacesSeam.CATALOG_QUERY) }
    }

    @Test
    fun `clearing ViewModel cancels offloaded bootstrap and prevents late READY publication`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        every { initializer.initialize() } returns Unit
        recoveringModel(initializer, DiscoveryAvailability.FAILED)
        val held = HeldCatalogDispatcher()
        viewModel.catalogDispatcher = held
        viewModel.selectCategory(PoiCategory.FUEL)
        runCurrent()
        val before = viewModel.uiState.value
        ViewModelStore().apply { put("recovery", viewModel) }.clear()
        held.runPending()
        repository.generation.value += 1
        repository.availability.value = DiscoveryAvailability.READY
        advanceUntilIdle()
        verify(exactly = 0) { initializer.initialize() }
        verify(exactly = 0) { health.failure(PlacesSeam.CATALOG_BOOTSTRAP) }
        assertThat(repository.nearestCalls).isEmpty()
        assertThat(viewModel.uiState.value).isEqualTo(before)
    }

    @Test
    fun `initializer cancellation is not bootstrap failure and later Retry remains actionable`() = runTest(testDispatcher) {
        val initializer = mockk<SearchInitializer>()
        every { initializer.initialize() } throws CancellationException("fixture cancelled")
        recoveringModel(initializer, DiscoveryAvailability.FAILED)
        viewModel.loadEmergency()
        advanceUntilIdle()
        verify(exactly = 0) { health.failure(PlacesSeam.CATALOG_BOOTSTRAP) }
        assertThat(viewModel.uiState.value.emergencyLookupFailed).isTrue()
        assertThat(repository.nearestCalls).isEmpty()
        every { initializer.initialize() } answers {
            repository.byCategory[PoiCategory.FUEL] = listOf(fuel("After cancellation", 0.03))
            repository.generation.value += 1
            repository.availability.value = DiscoveryAvailability.READY
        }
        viewModel.selectCategory(PoiCategory.FUEL)
        advanceUntilIdle()
        verify(exactly = 2) { initializer.initialize() }
        assertThat(viewModel.uiState.value.results.single().result.name).isEqualTo("After cancellation")
        assertThat(viewModel.uiState.value.categoryLookupFailed).isFalse()
    }

    private fun recoveringModel(initializer: SearchInitializer, availability: DiscoveryAvailability) {
        // Replace the setup model, not its sole repository observer with a second live owner.
        ViewModelStore().apply { put("setup", viewModel) }.clear()
        repository.availability.value = availability
        viewModel = NearbyViewModel(repository, health, initializer).apply {
            workDispatcher = testDispatcher
            catalogDispatcher = testDispatcher
        }
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

/** Holds only the offloaded bootstrap dispatch, exposing cancellation and in-flight coalescing. */
private class HeldCatalogDispatcher : CoroutineDispatcher() {
    private val pending = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
    fun runPending() {
        while (pending.isNotEmpty()) pending.removeFirst().run()
    }
}

/**
 * Minimal in-memory [SearchRepository] for the Nearby tests. Only [nearestByCategory] is exercised;
 * everything else is a no-op/empty stub.
 */
private class FakeSearchRepository : SearchRepository, PlaceDiscoveryRepository {
    override val availability = MutableStateFlow(DiscoveryAvailability.READY)
    override val generation = MutableStateFlow(0L)

    override suspend fun discover(request: DiscoveryRequest): List<SearchResult> = emptyList()

    val byCategory: MutableMap<PoiCategory, List<SearchResult>> = mutableMapOf()
    var throwOnNearest: Boolean = false
    var snapshotBeforeDelay: Boolean = false
    val nearestCalls = mutableListOf<PoiCategory>()
    val nearestOrigins = mutableListOf<Pair<Double, Double>>()

    /**
     * Per-category artificial latency (virtual ms) so tests can force a slow earlier query to land
     * after a faster later one.
     */
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
        nearestCalls += category
        nearestOrigins += latitude to longitude
        if (throwOnNearest) throw RuntimeException("simulated index failure")
        val snapshot = byCategory[category].orEmpty().take(limit)
        delayByCategory[category]?.let { delay(it) }
        return if (snapshotBeforeDelay) snapshot else byCategory[category].orEmpty().take(limit)
    }

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
