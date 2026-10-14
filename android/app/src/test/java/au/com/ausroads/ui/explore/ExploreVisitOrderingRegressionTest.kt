package au.com.ausroads.ui.explore

import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.di.SearchInitializer
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.DiscoveryRequest
import au.com.ausroads.offline.search.ExploreCategory
import au.com.ausroads.offline.search.PlaceDiscoveryRepository
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Uses only pre-repair APIs so this exact file can run on the unchanged baseline. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreVisitOrderingRegressionTest {
    private val dispatcher = StandardTestDispatcher()
    private val place = SearchResult("Later coffee", "poi", "amenity=cafe", -34.92, 138.62)
    private val older = place.copy(name = "Earlier coffee")
    private lateinit var store: HistoryIntentStore
    private lateinit var health: PlacesHealth
    private lateinit var vm: ExploreViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        store = HistoryIntentStore()
        val initializer = mockk<SearchInitializer>(relaxed = true)
        every { initializer.usingBundled } returns MutableStateFlow(true)
        health = mockk(relaxed = true)
        vm = ExploreViewModel(HistoryIntentCatalog(), store, initializer, health)
            .apply { workDispatcher = dispatcher }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `queued Undo survives emit then throw read fault until read Retry`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        val notice = vm.state.value.visitNotice!!
        val gate = CompletableDeferred<Unit>()
        store.interestGate = gate
        vm.setInterest(ExploreCategory.NATURE, true)
        runCurrent()
        assertThat(vm.state.value.isSaving).isTrue()
        vm.setVisited(notice.result, !notice.marked)
        vm.consumeVisitNotice(notice.id)
        store.readFault.value = true // The subscribed flow already emitted saved preferences.
        runCurrent()
        assertThat(vm.state.value.preferencesReadFailed).isTrue()
        assertThat(vm.state.value.preferences).isNull()
        assertThat(vm.state.value.isSaving).isTrue()
        assertThat(vm.state.value.visitNotice).isNull()
        verify(exactly = 1) { health.failure(PlacesSeam.PREFERENCES_READ) }

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(store.operations).containsExactly("visit:true", "interest").inOrder()
        assertThat(store.value.value.visits).containsExactly(place.placeKey(), ExploreCategory.COFFEE)
        assertThat(store.value.value.interests).containsExactly(ExploreCategory.NATURE)
        assertThat(vm.state.value.isSaving).isFalse()
        assertThat(vm.state.value.isLoading).isFalse()
        assertThat(store.readSubscriptions).isEqualTo(1) // No automatic read retry or queue spin.
        vm.retryWrite()
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.operations).containsExactly("visit:true", "interest").inOrder()

        store.readFault.value = false
        vm.retry()
        advanceUntilIdle()
        assertThat(store.readSubscriptions).isEqualTo(2)
        assertThat(store.operations).containsExactly("visit:true", "interest", "visit:false").inOrder()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(vm.state.value.preferences?.interests).containsExactly(ExploreCategory.NATURE)
        assertThat(vm.state.value.preferences?.visits).isEmpty()
        assertThat(vm.state.value.preferencesReadFailed).isFalse()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(vm.state.value.isSaving).isFalse()
        val undone = vm.state.value.visitNotice!!
        assertThat(undone.result).isEqualTo(notice.result)
        assertThat(undone.marked).isFalse()
        assertThat(undone.id).isGreaterThan(notice.id)
        vm.consumeVisitNotice(notice.id)
        assertThat(vm.state.value.visitNotice).isEqualTo(undone)
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.operations).containsExactly("visit:true", "interest", "visit:false").inOrder()
        verify(exactly = 0) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
        verify(exactly = 3) { health.success(PlacesSeam.PREFERENCES_WRITE) }
    }

    @Test
    fun `read recovery during held save keeps accepted visit intents ordered`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        val notice = vm.state.value.visitNotice!!
        val gate = CompletableDeferred<Unit>()
        store.interestGate = gate
        vm.setInterest(ExploreCategory.NATURE, true)
        runCurrent()
        vm.setVisited(notice.result, !notice.marked)
        vm.consumeVisitNotice(notice.id)
        vm.setVisited(place, true) // A separate later explicit mark, not another snackbar Undo.
        store.readFault.value = true
        runCurrent()
        assertThat(vm.state.value.preferencesReadFailed).isTrue()
        store.readFault.value = false
        vm.retry()
        runCurrent()
        assertThat(vm.state.value.preferencesReadFailed).isFalse()
        assertThat(vm.state.value.isSaving).isTrue()
        assertThat(store.operations).containsExactly("visit:true", "interest").inOrder()

        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(store.operations)
            .containsExactly("visit:true", "interest", "visit:false", "visit:true").inOrder()
        assertThat(store.value.value.visits).containsExactly(place.placeKey(), ExploreCategory.COFFEE)
        assertThat(store.value.value.interests).containsExactly(ExploreCategory.NATURE)
        assertThat(vm.state.value.visitNotice?.marked).isTrue()
        assertThat(vm.state.value.isSaving).isFalse()
        assertThat(store.readSubscriptions).isEqualTo(2)
        verify(exactly = 1) { health.failure(PlacesSeam.PREFERENCES_READ) }
        verify(exactly = 0) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
    }

    @Test
    fun `new confirmed clear supersedes paused clear retries but preserves queued interest`() = runTest(dispatcher) {
        val saved = ExplorationPreferences(visits = mapOf(older.placeKey() to ExploreCategory.COFFEE), preferNew = false)
        store.value.value = saved
        advanceUntilIdle()
        store.failInterest = true
        vm.setInterest(ExploreCategory.NATURE, true)
        advanceUntilIdle()
        store.failClear = true
        vm.clearVisits()
        advanceUntilIdle()
        store.readFault.value = true
        runCurrent()
        vm.retryWrite()
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.operations).containsExactly("interest", "clear").inOrder()
        assertThat(store.value.value).isEqualTo(saved)
        assertThat(vm.state.value.preferencesReadFailed).isTrue()
        assertThat(vm.state.value.isSaving).isFalse()

        store.failInterest = false
        store.failClear = false
        vm.clearVisits() // A new accepted confirmation supersedes the older queued clear.
        advanceUntilIdle()
        assertThat(store.operations).containsExactly("interest", "clear", "clear").inOrder()
        assertThat(store.value.value).isEqualTo(saved.copy(visits = emptyMap()))
        assertThat(vm.state.value.visitNotice).isNull()
        assertThat(vm.state.value.preferencesReadFailed).isTrue()
        store.readFault.value = false
        vm.retry()
        advanceUntilIdle()
        assertThat(store.operations).containsExactly("interest", "clear", "clear", "interest").inOrder()
        assertThat(store.value.value)
            .isEqualTo(saved.copy(visits = emptyMap(), interests = setOf(ExploreCategory.NATURE)))
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(vm.state.value.preferencesReadFailed).isFalse()
        verify(exactly = 2) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
        verify(exactly = 2) { health.failure(PlacesSeam.PREFERENCES_READ) }
    }

    @Test
    fun `failed clear cannot erase a later successful visit on Retry`() = runTest(dispatcher) {
        store.value.value = ExplorationPreferences(visits = mapOf(older.placeKey() to ExploreCategory.COFFEE))
        advanceUntilIdle()
        store.failClear = true
        vm.clearVisits()
        advanceUntilIdle()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        assertThat(store.value.value.visits).containsKey(older.placeKey())

        store.failClear = false
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(store.value.value.visits).containsKey(place.placeKey())
        vm.retryWrite() // On the baseline this replays ClearVisits and deletes both visits.
        advanceUntilIdle()

        assertThat(store.value.value.visits)
            .containsExactly(older.placeKey(), ExploreCategory.COFFEE, place.placeKey(), ExploreCategory.COFFEE)
        assertThat(store.operations).containsExactly("clear", "visit:true").inOrder()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(vm.state.value.visitNotice?.marked).isTrue()
    }

    @Test
    fun `successful visit supersedes failed clear while failed interest remains retryable`() = runTest(dispatcher) {
        advanceUntilIdle()
        store.failInterest = true
        vm.setInterest(ExploreCategory.NATURE, true)
        advanceUntilIdle()
        store.failClear = true
        vm.clearVisits()
        advanceUntilIdle()
        store.failClear = false
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        assertThat(store.value.value.interests).isEmpty()

        store.failInterest = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).containsKey(place.placeKey())
        assertThat(store.value.value.interests).containsExactly(ExploreCategory.NATURE)
        assertThat(store.operations).containsExactly("interest", "clear", "visit:true", "interest").inOrder()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
    }

    @Test
    fun `failed newer visit retains failed clear and Retry persists clear before visit`() = runTest(dispatcher) {
        store.value.value = ExplorationPreferences(visits = mapOf(older.placeKey() to ExploreCategory.COFFEE))
        advanceUntilIdle()
        store.failClear = true
        vm.clearVisits()
        advanceUntilIdle()
        store.failVisit = true
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(store.value.value.visits).containsKey(older.placeKey())
        assertThat(store.value.value.visits).doesNotContainKey(place.placeKey())
        assertThat(vm.state.value.visitNotice).isNull()

        store.failClear = false
        store.failVisit = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).containsExactly(place.placeKey(), ExploreCategory.COFFEE)
        assertThat(store.operations).containsExactly("clear", "visit:true", "clear", "visit:true").inOrder()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
    }

    @Test
    fun `queued successful removal supersedes failed clear and preserves failed interest`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        store.failClear = true
        vm.clearVisits()
        advanceUntilIdle()
        store.failClear = false
        store.failInterest = true
        val gate = CompletableDeferred<Unit>()
        store.interestGate = gate
        vm.setInterest(ExploreCategory.NATURE, true)
        runCurrent()
        vm.setVisited(place, false) // A new explicit remove, not an invalidated pre-clear snackbar.
        store.readFault.value = true
        runCurrent()
        assertThat(vm.state.value.preferencesReadFailed).isTrue()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(store.value.value.visits).containsExactly(place.placeKey(), ExploreCategory.COFFEE)
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        assertThat(store.operations).containsExactly("visit:true", "clear", "interest").inOrder()
        vm.retryWrite()
        vm.retryWrite() // Coalesce copies; the retained removal must supersede the older clear.
        advanceUntilIdle()
        assertThat(store.operations).containsExactly("visit:true", "clear", "interest").inOrder()
        store.failInterest = false
        store.readFault.value = false
        vm.retry()
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.value.value.interests).containsExactly(ExploreCategory.NATURE)
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(store.operations)
            .containsExactly("visit:true", "clear", "interest", "visit:false", "interest").inOrder()

        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.interests).containsExactly(ExploreCategory.NATURE)
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.operations)
            .containsExactly("visit:true", "clear", "interest", "visit:false", "interest").inOrder()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        verify(exactly = 2) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
        verify(exactly = 1) { health.failure(PlacesSeam.PREFERENCES_READ) }
    }
}

private class HistoryIntentCatalog : PlaceDiscoveryRepository {
    override val availability = MutableStateFlow(DiscoveryAvailability.READY)
    override val generation = MutableStateFlow(0L)
    override suspend fun discover(request: DiscoveryRequest): List<SearchResult> = emptyList()
}

private class HistoryIntentStore : ExplorationStore {
    val value = MutableStateFlow(ExplorationPreferences())
    val readFault = MutableStateFlow(false)
    var readSubscriptions = 0
    override val preferences = flow {
        readSubscriptions++
        emitAll(combine(value, readFault) { loaded, failed ->
            if (failed) throw IOException("fixture midstream read")
            loaded
        })
    }
    val operations = mutableListOf<String>()
    var failClear = false
    var failVisit = false
    var failInterest = false
    var interestGate: CompletableDeferred<Unit>? = null

    override suspend fun setInterest(category: ExploreCategory, enabled: Boolean) {
        operations += "interest"
        interestGate?.await()
        if (failInterest) throw IOException("fixture interest")
        value.value = value.value.copy(
            interests = if (enabled) value.value.interests + category else value.value.interests - category
        )
    }

    override suspend fun setPreferNew(enabled: Boolean) {
        value.value = value.value.copy(preferNew = enabled)
    }

    override suspend fun setVisited(result: SearchResult, visited: Boolean) {
        operations += "visit:$visited"
        if (failVisit) throw IOException("fixture visit")
        value.value = value.value.copy(
            visits =
                if (visited) value.value.visits + (result.placeKey() to ExploreCategory.COFFEE)
                else value.value.visits - result.placeKey()
        )
    }

    override suspend fun clearVisits() {
        operations += "clear"
        if (failClear) throw IOException("fixture clear")
        value.value = value.value.copy(visits = emptyMap())
    }
}
