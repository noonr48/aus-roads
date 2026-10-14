package au.com.ausroads.ui.explore

import au.com.ausroads.core.model.GeoPoint
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
class ExploreViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val place = SearchResult("Test coffee", "poi", "amenity=cafe", -34.92, 138.62)
    private lateinit var repo: TestCatalog
    private lateinit var store: TestStore
    private lateinit var health: PlacesHealth
    private lateinit var vm: ExploreViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        repo = TestCatalog { listOf(place) }
        store = TestStore()
        health = mockk(relaxed = true)
        val initializer = mockk<SearchInitializer>(relaxed = true)
        every { initializer.usingBundled } returns MutableStateFlow(true)
        vm =
            ExploreViewModel(repo, store, initializer, health).apply { workDispatcher = dispatcher }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `visit Undo during a suspended preference save is not discarded`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        val notice = vm.state.value.visitNotice!!
        val gate = CompletableDeferred<Unit>()
        store.preferenceGate = gate
        vm.setPreferNew(false)
        runCurrent()
        assertThat(vm.state.value.isSaving).isTrue()
        vm.setVisited(notice.result, !notice.marked) // actual snackbar command
        vm.consumeVisitNotice(notice.id) // actual snackbar finally path
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.visitWrites).isEqualTo(2)
        assertThat(store.value.value.preferNew).isFalse()
        assertThat(vm.state.value.isSaving).isFalse()
    }

    @Test
    fun `failed preference remains retryable after queued Undo succeeds`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        val notice = vm.state.value.visitNotice!!
        val gate = CompletableDeferred<Unit>()
        store.preferenceGate = gate
        store.failPreferNew = true
        vm.setPreferNew(false)
        runCurrent()
        vm.setVisited(notice.result, !notice.marked)
        vm.consumeVisitNotice(notice.id)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.value.value.preferNew).isTrue()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        verify { health.failure(PlacesSeam.PREFERENCES_WRITE) }
        store.failPreferNew = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.preferNew).isFalse()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
    }

    @Test
    fun `failed preference and queued Undo both survive one explicit retry`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        val notice = vm.state.value.visitNotice!!
        val gate = CompletableDeferred<Unit>()
        store.preferenceGate = gate
        store.failWrite = true
        vm.setPreferNew(false)
        runCurrent()
        vm.setVisited(notice.result, !notice.marked)
        vm.consumeVisitNotice(notice.id)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        assertThat(store.value.value.visits).hasSize(1)
        store.failWrite = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.value.value.preferNew).isFalse()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
    }

    @Test
    fun `successful confirmed clear supersedes a failed mark visit`() = runTest(dispatcher) {
        advanceUntilIdle()
        store.failWrite = true
        vm.setVisited(place, true)
        advanceUntilIdle()
        store.failWrite = false
        vm.clearVisits()
        advanceUntilIdle()
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.visitWrites).isEqualTo(0)
        assertThat(store.clearWrites).isEqualTo(1)
    }

    @Test
    fun `latest failed confirmed clear supersedes older failed visits before retry`() = runTest(dispatcher) {
        advanceUntilIdle()
        store.value.value = ExplorationPreferences(visits = mapOf(place.placeKey() to ExploreCategory.COFFEE))
        advanceUntilIdle()
        store.failWrite = true
        vm.clearVisits()
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        vm.clearVisits() // newest explicit confirmed intent, not the first map slot
        advanceUntilIdle()
        verify { health.failure(PlacesSeam.PREFERENCES_WRITE) }
        store.failWrite = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.visitWrites).isEqualTo(0) // never replay an older superseded visit
        assertThat(store.clearWrites).isEqualTo(1)
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(vm.state.value.visitNotice).isNull()
    }

    @Test
    fun `newer explicit failed visit after confirmed clear survives retry`() = runTest(dispatcher) {
        advanceUntilIdle()
        store.failWrite = true
        vm.clearVisits()
        advanceUntilIdle()
        vm.setVisited(place, true) // a NEW later intent, not superseded pre-clear work
        advanceUntilIdle()
        store.failWrite = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).containsKey(place.placeKey())
        assertThat(store.visitWrites).isEqualTo(1)
        assertThat(store.clearWrites).isEqualTo(1)
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(vm.state.value.visitNotice?.marked).isTrue()
    }

    @Test
    fun `new explicit visit queued during suspended confirmed clear remains actionable`() = runTest(dispatcher) {
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        store.clearGate = gate
        vm.clearVisits()
        runCurrent()
        assertThat(vm.state.value.isSaving).isTrue()
        vm.setVisited(place, true)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(store.value.value.visits).containsKey(place.placeKey())
        assertThat(store.visitWrites).isEqualTo(1)
        assertThat(store.clearWrites).isEqualTo(1)
        assertThat(vm.state.value.visitNotice?.marked).isTrue()
    }

    @Test
    fun `busy rejected clear does not supersede an older failed visit`() = runTest(dispatcher) {
        advanceUntilIdle()
        store.failWrite = true
        vm.setVisited(place, true)
        advanceUntilIdle()
        store.failWrite = false
        val gate = CompletableDeferred<Unit>()
        store.preferenceGate = gate
        vm.setPreferNew(false)
        runCurrent()
        vm.clearVisits() // ignored by admission while saving; no intent was accepted
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).containsKey(place.placeKey())
        assertThat(store.clearWrites).isEqualTo(0)
    }

    @Test
    fun `confirmed clear preserves unrelated failed preference retry`() = runTest(dispatcher) {
        advanceUntilIdle()
        store.failPreferNew = true
        vm.setPreferNew(false)
        advanceUntilIdle()
        vm.clearVisits()
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        store.failPreferNew = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.preferNew).isFalse()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
    }

    @Test
    fun `production visit transition allows 500 rejects 501 and preserves v1 records`() {
        val before = fullHistory().entries.take(499).associate { it.toPair() }
        val full = DataStoreExplorationStore.updatedVisits(before, place.placeKey(), ExploreCategory.COFFEE, true)
        assertThat(full).hasSize(500)
        assertThat(full.entries).containsAtLeastElementsIn(before.entries)
        val extra = place.copy(name = "Extra coffee")
        org.junit.Assert.assertThrows(VisitLimitReachedException::class.java) {
            DataStoreExplorationStore.updatedVisits(full, extra.placeKey(), ExploreCategory.COFFEE, true)
        }
        assertThat(full).hasSize(500)
        assertThat(full).doesNotContainKey(extra.placeKey())
        assertThat(DataStoreExplorationStore.updatedVisits(full, place.placeKey(), ExploreCategory.COFFEE, true))
            .isEqualTo(full) // Re-marking an existing identity consumes no extra slot.
        val records = full.map { (key, category) -> "$key;${category.name}" }.toSet()
        assertThat(DataStoreExplorationStore.decodeVisits(records)).isEqualTo(full)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            DataStoreExplorationStore.decodeVisits(records + "${extra.placeKey()};COFFEE")
        } // Oversized persisted data is still a read error, never silently truncated.
    }

    @Test
    fun `visit decoder accepts empty and distinct v1 records without changing categories`() {
        val other = place.copy(name = "Other coffee").placeKey()
        val records = linkedSetOf("${place.placeKey()};COFFEE", "$other;NATURE")
        val expected = mapOf(place.placeKey() to ExploreCategory.COFFEE, other to ExploreCategory.NATURE)
        assertThat(DataStoreExplorationStore.decodeVisits(emptySet())).isEmpty()
        assertThat(DataStoreExplorationStore.decodeVisits(records)).isEqualTo(expected)
        assertThat(DataStoreExplorationStore.decodeVisits(records.toList().asReversed().toSet())).isEqualTo(expected)
    }

    @Test
    fun `visit decoder rejects conflicting categories for one v1 key in either order`() {
        val records = linkedSetOf("${place.placeKey()};COFFEE", "${place.placeKey()};NATURE")
        listOf(records, records.toList().asReversed().toSet()).forEach { ordered ->
            val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                DataStoreExplorationStore.decodeVisits(ordered)
            }
            assertThat(failure).hasMessageThat().isEqualTo("Conflicting visit records")
            assertThat(ordered).containsExactlyElementsIn(records)
        }
    }

    @Test
    fun `visit 501 is capacity not storage failure and Retry never evicts history`() = runTest(dispatcher) {
        val saved = ExplorationPreferences(setOf(ExploreCategory.NATURE), fullHistory(), preferNew = false)
        store.value.value = saved
        advanceUntilIdle()
        assertThat(vm.state.value.visitLimitReached).isFalse()
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(store.value.value).isEqualTo(saved)
        assertThat(vm.state.value.preferences).isEqualTo(saved)
        assertThat(vm.state.value.visitLimitReached).isTrue()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(vm.state.value.visitNotice).isNull()
        assertThat(vm.state.value.isSaving).isFalse()
        verify(exactly = 0) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
        vm.retryWrite()
        advanceUntilIdle()
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.visitAttempts).isEqualTo(1)
        assertThat(store.visitWrites).isEqualTo(0)
        assertThat(store.value.value).isEqualTo(saved)
    }

    @Test
    fun `capacity preserves unrelated failed interest intent for Retry`() = runTest(dispatcher) {
        val history = fullHistory()
        store.value.value = ExplorationPreferences(visits = history)
        advanceUntilIdle()
        store.failInterest = true
        vm.setInterest(ExploreCategory.NATURE, true)
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(vm.state.value.visitLimitReached).isTrue()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        store.failInterest = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.interests).containsExactly(ExploreCategory.NATURE)
        assertThat(store.value.value.visits).isEqualTo(history)
        assertThat(store.visitAttempts).isEqualTo(1)
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(vm.state.value.visitLimitReached).isTrue() // An interest save did not free a slot.
        verify(exactly = 1) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
    }

    @Test
    fun `capacity dismiss and explicit remove then mark never delete another visit`() = runTest(dispatcher) {
        val history = fullHistory()
        store.value.value = ExplorationPreferences(visits = history)
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        vm.dismissWriteError()
        assertThat(vm.state.value.visitLimitReached).isFalse()
        assertThat(store.value.value.visits).isEqualTo(history)
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(vm.state.value.visitLimitReached).isTrue()
        val removed = place.copy(name = "Saved coffee 0")
        vm.setVisited(removed, false)
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEqualTo(history - removed.placeKey())
        assertThat(vm.state.value.visitLimitReached).isFalse()
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(store.value.value.visits)
            .isEqualTo((history - removed.placeKey()) + (place.placeKey() to ExploreCategory.COFFEE))
        assertThat(vm.state.value.visitLimitReached).isFalse()
        assertThat(vm.state.value.visitNotice?.marked).isTrue()
    }

    @Test
    fun `confirmed clear releases capacity while preserving interests and new-first`() = runTest(dispatcher) {
        store.value.value = ExplorationPreferences(setOf(ExploreCategory.NATURE), fullHistory(), preferNew = false)
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(vm.state.value.visitLimitReached).isTrue()
        vm.clearVisits()
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.value.value.interests).containsExactly(ExploreCategory.NATURE)
        assertThat(store.value.value.preferNew).isFalse()
        assertThat(vm.state.value.visitLimitReached).isFalse()
        assertThat(store.clearWrites).isEqualTo(1)
    }

    @Test
    fun `retried storage failure reaching capacity is removed from Retry without optimistic success`() = runTest(dispatcher) {
        val history = fullHistory()
        store.value.value = ExplorationPreferences(visits = history)
        advanceUntilIdle()
        store.failWrite = true
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        assertThat(vm.state.value.visitLimitReached).isFalse()
        store.failWrite = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(vm.state.value.visitLimitReached).isTrue()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
        assertThat(vm.state.value.visitNotice).isNull()
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.visitAttempts).isEqualTo(2)
        assertThat(store.value.value.visits).isEqualTo(history)
        verify(exactly = 1) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
    }

    @Test
    fun `capacity refusal cannot supersede an older failed clear`() = runTest(dispatcher) {
        store.value.value = ExplorationPreferences(visits = fullHistory())
        advanceUntilIdle()
        store.failClear = true
        vm.clearVisits()
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        assertThat(vm.state.value.visitLimitReached).isTrue()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        store.failClear = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.clearWrites).isEqualTo(1)
        assertThat(store.visitAttempts).isEqualTo(1)
        assertThat(vm.state.value.visitLimitReached).isFalse()
        assertThat(vm.state.value.preferencesWriteFailed).isFalse()
    }

    @Test
    fun `cancelled newer visit retains failed clear without another storage failure witness`() = runTest(dispatcher) {
        store.value.value = ExplorationPreferences(visits = mapOf(place.placeKey() to ExploreCategory.COFFEE))
        advanceUntilIdle()
        store.failClear = true
        vm.clearVisits()
        advanceUntilIdle()
        store.failClear = false
        store.cancelVisit = true
        vm.setVisited(place.copy(name = "Cancelled coffee"), true)
        advanceUntilIdle()
        assertThat(vm.state.value.preferencesWriteFailed).isTrue()
        assertThat(vm.state.value.visitNotice).isNull()
        assertThat(vm.state.value.isSaving).isFalse()
        store.cancelVisit = false
        vm.retryWrite()
        advanceUntilIdle()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.clearWrites).isEqualTo(1)
        assertThat(store.visitWrites).isEqualTo(0)
        assertThat(store.visitAttempts).isEqualTo(1)
        verify(exactly = 1) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
    }

    private fun fullHistory(): Map<String, ExploreCategory> =
        (0 until DataStoreExplorationStore.MAX_VISITS).associate {
            place.copy(name = "Saved coffee $it").placeKey() to ExploreCategory.COFFEE
        }

    @Test
    fun `idle visit Undo and new-first off on persist without implicit extra visits`() = runTest(dispatcher) {
        advanceUntilIdle()
        vm.setVisited(place, true)
        advanceUntilIdle()
        vm.setVisited(place, false)
        advanceUntilIdle()
        vm.setPreferNew(false)
        advanceUntilIdle()
        assertThat(store.value.value.preferNew).isFalse()
        assertThat(vm.state.value.preferences?.preferNew).isFalse()
        vm.setPreferNew(true)
        advanceUntilIdle()
        assertThat(store.value.value.preferNew).isTrue()
        assertThat(store.value.value.visits).isEmpty()
        assertThat(store.visitWrites).isEqualTo(2)
    }

    @Test
    fun `query length and radius internal contract boundaries are pinned`() =
        runTest(dispatcher) {
            vm.setQuery("x".repeat(200))
            advanceUntilIdle()
            assertThat(vm.state.value.query.length).isEqualTo(DiscoveryRequest.MAX_QUERY_LENGTH)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { vm.setRadius(0) }
            vm.setRadius(50)
            advanceUntilIdle()
            assertThat(vm.state.value.radiusKm).isEqualTo(50)
        }

    @Test
    fun `identical discovery query keeps completed results and does not report empty`() =
        runTest(dispatcher) {
            vm.setQuery("coffee")
            advanceUntilIdle()
            assertThat(vm.state.value.results).hasSize(1)
            vm.setQuery("coffee")
            advanceUntilIdle()
            assertThat(vm.state.value.results).hasSize(1)
            assertThat(vm.state.value.isLoading).isFalse()
        }

    @Test
    fun `same-turn discovery A to B to A edits complete the returned query`() =
        runTest(dispatcher) {
            vm.setQuery("coffee")
            advanceUntilIdle()
            repo.handler = { listOf(place.copy(name = "RETURNED")) }
            vm.setQuery("coffeeX")
            vm.setQuery("coffee")
            advanceUntilIdle()
            assertThat(vm.state.value.results.single().result.name).isEqualTo("RETURNED")
            assertThat(vm.state.value.isLoading).isFalse()
        }

    @Test
    fun `query interest and map movement never record visits implicitly`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            vm.setQuery("coffee")
            vm.setReference(GeoPoint(longitude = 138.621, latitude = -34.92))
            advanceUntilIdle()
            vm.setInterest(ExploreCategory.COFFEE, true)
            advanceUntilIdle()
            assertThat(store.value.value.visits).isEmpty()
            assertThat(store.visitWrites).isEqualTo(0)
            assertThat(vm.state.value.results).isNotEmpty()
        }

    @Test
    fun `write kill produces exact witness and no optimistic visit then exact retry succeeds`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            store.failWrite = true
            vm.setVisited(place, true)
            advanceUntilIdle()
            assertThat(store.value.value.visits).isEmpty()
            assertThat(vm.state.value.preferencesWriteFailed).isTrue()
            assertThat(vm.state.value.visitNotice).isNull()
            verify { health.failure(PlacesSeam.PREFERENCES_WRITE) }
            store.failWrite = false
            vm.retryWrite()
            advanceUntilIdle()
            assertThat(store.value.value.visits).containsKey(place.placeKey())
            assertThat(vm.state.value.preferencesWriteFailed).isFalse()
            assertThat(vm.state.value.visitNotice?.marked).isTrue()
        }

    @Test
    fun `consumed notice cannot replay and clearing history invalidates an old undo`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            vm.setVisited(place, true)
            advanceUntilIdle()
            val first = vm.state.value.visitNotice!!
            vm.consumeVisitNotice(first.id)
            assertThat(vm.state.value.visitNotice).isNull()
            vm.setVisited(place, false)
            advanceUntilIdle()
            assertThat(vm.state.value.visitNotice).isNotNull()
            vm.clearVisits()
            advanceUntilIdle()
            assertThat(vm.state.value.visitNotice).isNull()
            assertThat(store.value.value.visits).isEmpty()
        }

    @Test
    fun `read failure is not empty preferences and retry restores the stored interests`() =
        runTest(dispatcher) {
            // A genuine first-read failure is configured before the subscribing ViewModel exists.
            store = TestStore().apply { failRead = true }
            val failedInitializer = mockk<SearchInitializer>(relaxed = true)
            every { failedInitializer.usingBundled } returns MutableStateFlow(true)
            vm =
                ExploreViewModel(repo, store, failedInitializer, health).apply {
                    workDispatcher = dispatcher
                }
            advanceUntilIdle()
            assertThat(vm.state.value.preferencesReadFailed).isTrue()
            assertThat(vm.state.value.isLoading).isFalse()
            verify { health.failure(PlacesSeam.PREFERENCES_READ) }
            store.value.value = ExplorationPreferences(interests = setOf(ExploreCategory.COFFEE))
            store.failRead = false
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.state.value.preferences?.interests)
                .containsExactly(ExploreCategory.COFFEE)
            assertThat(vm.state.value.preferencesReadFailed).isFalse()
        }

    @Test
    fun `conflicting visits stay unreadable until confirmed clear succeeds without resetting preferences`() =
        runTest(dispatcher) {
            val saved = ExplorationPreferences(
                interests = setOf(ExploreCategory.NATURE),
                visits = mapOf(place.placeKey() to ExploreCategory.COFFEE),
                preferNew = false,
            )
            val records = setOf("${place.placeKey()};COFFEE", "${place.placeKey()};NATURE")
            store = TestStore().apply {
                value.value = saved
                visitRecords = records
            }
            val initializer = mockk<SearchInitializer>(relaxed = true)
            every { initializer.usingBundled } returns MutableStateFlow(true)
            vm = ExploreViewModel(repo, store, initializer, health).apply { workDispatcher = dispatcher }
            advanceUntilIdle()
            assertThat(vm.state.value.preferencesReadFailed).isTrue()
            assertThat(vm.state.value.preferences).isNull()
            assertThat(vm.state.value.isLoading).isFalse()
            assertThat(vm.state.value.results).isEmpty()
            verify(exactly = 1) { health.failure(PlacesSeam.PREFERENCES_READ) }

            vm.setInterest(ExploreCategory.COFFEE, true)
            vm.setPreferNew(true)
            vm.setVisited(place, false)
            vm.retry()
            advanceUntilIdle()
            assertThat(store.value.value).isEqualTo(saved)
            assertThat(store.visitRecords).isEqualTo(records)
            assertThat(store.visitAttempts).isEqualTo(0)
            assertThat(store.clearWrites).isEqualTo(0)
            assertThat(vm.state.value.preferencesReadFailed).isTrue()
            verify(exactly = 2) { health.failure(PlacesSeam.PREFERENCES_READ) }
            verify(exactly = 0) { health.failure(PlacesSeam.PREFERENCES_WRITE) }

            store.failClear = true
            vm.clearVisits() // Separately confirmed history recovery, not implicit read recovery.
            advanceUntilIdle()
            assertThat(store.value.value).isEqualTo(saved)
            assertThat(store.visitRecords).isEqualTo(records)
            assertThat(store.clearWrites).isEqualTo(0)
            assertThat(vm.state.value.preferencesWriteFailed).isTrue()
            store.failClear = false
            vm.retryWrite()
            advanceUntilIdle()
            assertThat(store.clearWrites).isEqualTo(1)
            assertThat(store.value.value).isEqualTo(saved.copy(visits = emptyMap()))
            assertThat(store.visitRecords).isEmpty()
            assertThat(vm.state.value.preferences).isEqualTo(saved.copy(visits = emptyMap()))
            assertThat(vm.state.value.preferencesReadFailed).isFalse()
            assertThat(vm.state.value.preferencesWriteFailed).isFalse()
            assertThat(vm.state.value.visitNotice).isNull()
            assertThat(store.visitAttempts).isEqualTo(0)
            verify(exactly = 1) { health.failure(PlacesSeam.PREFERENCES_WRITE) }
            verify(exactly = 1) { health.success(PlacesSeam.PREFERENCES_WRITE) }
            verify(atLeast = 1) { health.success(PlacesSeam.PREFERENCES_READ) }
        }

    @Test
    fun `invalid visits are never silently reset but confirmed clear restores saved interests`() =
        runTest(dispatcher) {
            store =
                TestStore().apply {
                    malformedVisits = true
                    value.value =
                        ExplorationPreferences(
                            interests = setOf(ExploreCategory.NATURE),
                            visits = mapOf(place.placeKey() to ExploreCategory.COFFEE),
                            preferNew = false,
                        )
                }
            val initializer = mockk<SearchInitializer>(relaxed = true)
            every { initializer.usingBundled } returns MutableStateFlow(true)
            vm =
                ExploreViewModel(repo, store, initializer, health).apply {
                    workDispatcher = dispatcher
                }
            advanceUntilIdle()
            assertThat(vm.state.value.preferencesReadFailed).isTrue()
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.state.value.preferencesReadFailed).isTrue()
            assertThat(store.clearWrites).isEqualTo(0)
            vm.clearVisits() // The UI invokes only after its explicit confirmation dialog.
            advanceUntilIdle()
            assertThat(store.clearWrites).isEqualTo(1)
            assertThat(vm.state.value.preferencesReadFailed).isFalse()
            assertThat(vm.state.value.preferences?.visits).isEmpty()
            assertThat(vm.state.value.preferences?.interests)
                .containsExactly(ExploreCategory.NATURE)
            assertThat(vm.state.value.preferences?.preferNew).isFalse()
        }

    @Test
    fun `query failure is not no matches and can be retried`() =
        runTest(dispatcher) {
            repo.handler = { throw IOException("fixture") }
            advanceUntilIdle()
            assertThat(vm.state.value.queryFailed).isTrue()
            assertThat(vm.state.value.isLoading).isFalse()
            verify { health.failure(PlacesSeam.CATALOG_QUERY) }
            repo.handler = { listOf(place) }
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.state.value.queryFailed).isFalse()
            assertThat(vm.state.value.results.single().result).isEqualTo(place)
        }

    @Test
    fun `catalog ready replacement refreshes unchanged query and explicit unavailable is distinct`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            repo.availability.value = DiscoveryAvailability.UNAVAILABLE
            repo.generation.value += 1
            advanceUntilIdle()
            assertThat(vm.state.value.results).isEmpty()
            assertThat(vm.state.value.catalog).isEqualTo(DiscoveryAvailability.UNAVAILABLE)
            val replacement = place.copy(name = "Replacement")
            repo.handler = { listOf(replacement) }
            repo.availability.value = DiscoveryAvailability.READY
            repo.generation.value += 1
            advanceUntilIdle()
            assertThat(vm.state.value.results.single().result).isEqualTo(replacement)
        }

    @Test
    fun `superseded query cancels and cannot publish stale success or failure`() =
        runTest(dispatcher) {
            advanceUntilIdle()
            var cancelled = false
            repo.handler = { request ->
                if (request.query == "slow") {
                    try {
                        delay(1_000)
                    } catch (cause: CancellationException) {
                        cancelled = true
                        throw cause
                    }
                    listOf(place.copy(name = "OLD"))
                } else listOf(place.copy(name = "NEW"))
            }
            vm.setQuery("slow")
            advanceTimeBy(251)
            runCurrent()
            vm.setQuery("fast")
            advanceUntilIdle()
            assertThat(cancelled).isTrue()
            assertThat(vm.state.value.query).isEqualTo("fast")
            assertThat(vm.state.value.results.single().result.name).isEqualTo("NEW")
            assertThat(vm.state.value.queryFailed).isFalse()
            assertThat(vm.state.value.isLoading).isFalse()
        }
}

private class TestCatalog(var handler: suspend (DiscoveryRequest) -> List<SearchResult>) :
    PlaceDiscoveryRepository {
    override val availability = MutableStateFlow(DiscoveryAvailability.READY)
    override val generation = MutableStateFlow(0L)

    override suspend fun discover(request: DiscoveryRequest) = handler(request)
}

private class TestStore : ExplorationStore {
    val value = MutableStateFlow(ExplorationPreferences())
    var failRead = false
    var failWrite = false
    var failPreferNew = false
    var failInterest = false
    var failClear = false
    var cancelVisit = false
    var visitAttempts = 0
    var visitWrites = 0
    var clearWrites = 0
    var malformedVisits = false
    var visitRecords: Set<String>? = null
    var preferenceGate: CompletableDeferred<Unit>? = null
    var clearGate: CompletableDeferred<Unit>? = null
    override val preferences = flow {
        if (failRead) throw IOException("fixture read")
        if (malformedVisits) DataStoreExplorationStore.decodeVisits(setOf("invalid record"))
        emitAll(value.map { loaded ->
            loaded.copy(visits = visitRecords?.let { DataStoreExplorationStore.decodeVisits(it) } ?: loaded.visits)
        })
    }

    private fun writable() {
        if (failWrite) throw IOException("fixture write")
    }

    override suspend fun setInterest(category: ExploreCategory, enabled: Boolean) {
        writable()
        if (failInterest) throw IOException("fixture interest write")
        value.value =
            value.value.copy(
                interests =
                    if (enabled) value.value.interests + category
                    else value.value.interests - category
            )
    }

    override suspend fun setPreferNew(enabled: Boolean) {
        preferenceGate?.await()
        if (failPreferNew) throw IOException("fixture preference write")
        writable()
        value.value = value.value.copy(preferNew = enabled)
    }

    override suspend fun setVisited(result: SearchResult, visited: Boolean) {
        visitAttempts++
        if (cancelVisit) throw CancellationException("fixture visit cancelled")
        writable()
        val visits = DataStoreExplorationStore.updatedVisits(
            value.value.visits, result.placeKey(), ExploreCategory.COFFEE, visited,
        )
        visitWrites++
        value.value = value.value.copy(visits = visits)
    }

    override suspend fun clearVisits() {
        clearGate?.await()
        writable()
        if (failClear) throw IOException("fixture clear write")
        clearWrites++
        malformedVisits = false
        visitRecords = visitRecords?.let { emptySet() }
        value.value = value.value.copy(visits = emptyMap())
    }
}
