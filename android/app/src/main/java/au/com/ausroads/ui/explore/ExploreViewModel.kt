package au.com.ausroads.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class ExploreFilters(
    val reference: GeoPoint = GeoPoint(longitude = 138.62, latitude = -34.92),
    val query: String = "",
    val category: ExploreCategory? = null,
    val radiusKm: Int = 20,
)

data class VisitNotice(val id: Long, val result: SearchResult, val marked: Boolean)

data class ExploreUiState(
    val reference: GeoPoint = GeoPoint(longitude = 138.62, latitude = -34.92),
    val query: String = "",
    val category: ExploreCategory? = null,
    val radiusKm: Int = 20,
    val results: List<SuggestedPlace> = emptyList(),
    val preferences: ExplorationPreferences? = null,
    val catalog: DiscoveryAvailability = DiscoveryAvailability.LOADING,
    val usingBundledCatalog: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val queryFailed: Boolean = false,
    val preferencesReadFailed: Boolean = false,
    val preferencesWriteFailed: Boolean = false,
    val visitNotice: VisitNotice? = null,
    val visitLimitReached: Boolean = false,
)

@Suppress(
    "TooManyFunctions",
    "TooGenericExceptionCaught",
) // explicit UI commands; boundary failures stay typed and retryable
@HiltViewModel
class ExploreViewModel
@Inject
constructor(
    private val repository: PlaceDiscoveryRepository,
    private val store: ExplorationStore,
    private val initializer: SearchInitializer,
    private val health: PlacesHealth,
) : ViewModel() {
    internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val filters = MutableStateFlow(ExploreFilters())
    private val preferences = MutableStateFlow<ExplorationPreferences?>(null)
    private val refresh = MutableStateFlow(0L)
    private val _state = MutableStateFlow(ExploreUiState())
    val state = _state.asStateFlow()
    private var preferencesJob: Job? = null
    private val failedMutations = linkedMapOf<String, Mutation>()
    private val pendingWrites = ArrayDeque<PendingWrite>()
    private var noticeId = 0L

    init {
        observePreferences()
        viewModelScope.launch {
            val revision =
                combine(repository.generation, refresh) { generation, requestSequence ->
                    Revision(generation, requestSequence)
                }
            combine(
                    filters,
                    repository.availability,
                    revision,
                    preferences,
                    initializer.usingBundled,
                ) { currentFilters, availability, currentRevision, currentPreferences, bundled ->
                    Snapshot(
                        currentFilters,
                        availability,
                        currentRevision,
                        currentPreferences,
                        bundled,
                    )
                }
                .collectLatest { snapshot ->
                    _state.update {
                        it.copy(
                            reference = snapshot.filters.reference,
                            query = snapshot.filters.query,
                            category = snapshot.filters.category,
                            radiusKm = snapshot.filters.radiusKm,
                            preferences = snapshot.preferences,
                            catalog = snapshot.availability,
                            usingBundledCatalog = snapshot.bundled,
                            results = emptyList(),
                            queryFailed = false,
                            isLoading =
                                snapshot.availability == DiscoveryAvailability.LOADING ||
                                    (snapshot.preferences == null && !it.preferencesReadFailed),
                        )
                    }
                    if (
                        snapshot.availability != DiscoveryAvailability.READY ||
                            snapshot.preferences == null
                    ) {
                        return@collectLatest
                    }
                    _state.update { it.copy(isLoading = true) }
                    try {
                        if (snapshot.filters.query.isNotBlank()) delay(QUERY_DELAY_MS)
                        val request =
                            DiscoveryRequest(
                                latitude = snapshot.filters.reference.latitude,
                                longitude = snapshot.filters.reference.longitude,
                                radiusKm = snapshot.filters.radiusKm,
                                query = snapshot.filters.query,
                                category = snapshot.filters.category,
                            )
                        val candidates = repository.discover(request)
                        val ranked =
                            withContext(workDispatcher) {
                                ExplorationRanking.rank(candidates, request, snapshot.preferences)
                            }
                        coroutineContext.ensureActive()
                        if (isCurrent(snapshot)) {
                            _state.update { it.copy(results = ranked) }
                            health.success(PlacesSeam.CATALOG_QUERY)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (isCurrent(snapshot)) {
                            health.failure(PlacesSeam.CATALOG_QUERY)
                            _state.update { it.copy(queryFailed = true, results = emptyList()) }
                        }
                    } finally {
                        if (isCurrent(snapshot)) _state.update { it.copy(isLoading = false) }
                    }
                }
        }
    }

    fun setReference(point: GeoPoint) {
        filters.update { it.copy(reference = point) }
    }

    fun setQuery(value: String) {
        val query = value.take(DiscoveryRequest.MAX_QUERY_LENGTH)
        if (filters.value.query == query && filters.value.category == null) return
        filters.update { it.copy(query = query, category = null) }
        // The request revision also records edits that StateFlow conflates (A→B→A).
        // Identical events above preserve completed results rather than falsely show no matches.
        refresh.value += 1L
        _state.update { it.copy(query = query, results = emptyList()) }
    }

    fun setCategory(value: ExploreCategory?) {
        filters.update { it.copy(category = value, query = "") }
    }

    fun setRadius(value: Int) {
        require(value in RADII)
        filters.update { it.copy(radiusKm = value) }
    }

    fun setInterest(category: ExploreCategory, enabled: Boolean) =
        mutate(Mutation.Interest(category, enabled))

    fun setPreferNew(enabled: Boolean) = mutate(Mutation.NewFirst(enabled))

    fun setVisited(result: SearchResult, visited: Boolean) = mutate(Mutation.Visit(result, visited))

    fun clearVisits() {
        // Only an ACCEPTED new confirmation supersedes older visit intent.
        // Internal Retry replays mutate() directly, so it must not discard a
        // genuinely newer visit already ordered after that clear.
        if (_state.value.isSaving || (preferences.value == null && !_state.value.preferencesReadFailed)) return
        failedMutations.keys.removeAll { it.startsWith("visit:") }
        pendingWrites.removeAll { it.mutation is Mutation.Visit || it.mutation == Mutation.ClearVisits }
        _state.update { it.copy(visitNotice = null) }
        mutate(Mutation.ClearVisits)
    }

    fun consumeVisitNotice(id: Long) {
        _state.update { if (it.visitNotice?.id == id) it.copy(visitNotice = null) else it }
    }

    fun retryWrite() {
        if (_state.value.isSaving) return
        // Retry remains explicit even while a read fault pauses accepted work.
        // Coalesce copies, retaining unrelated failed preferences behind that work.
        failedMutations.values.forEach { mutation ->
            if (pendingWrites.none { it.mutation == mutation }) {
                pendingWrites.addLast(PendingWrite(mutation, isRetry = true))
            }
        }
        drainPendingWrite()
    }

    fun dismissWriteError() {
        _state.update { it.copy(preferencesWriteFailed = false, visitLimitReached = false) }
    }

    fun retry() {
        if (_state.value.preferencesReadFailed) observePreferences()
        viewModelScope.launch {
            if (repository.availability.value != DiscoveryAvailability.READY) {
                withContext(Dispatchers.IO) { initializer.initialize() }
            }
            refresh.value += 1L
        }
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // DataStore read failures remain a visible, retryable state
    private fun observePreferences() {
        preferencesJob?.cancel()
        _state.update { it.copy(preferencesReadFailed = false, isLoading = true) }
        preferencesJob = viewModelScope.launch {
            try {
                store.preferences.collect { loaded ->
                    preferences.value = loaded
                    _state.update { it.copy(preferencesReadFailed = false, preferences = loaded) }
                    health.success(PlacesSeam.PREFERENCES_READ)
                    drainPendingWrite()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                preferences.value = null
                health.failure(PlacesSeam.PREFERENCES_READ)
                _state.update {
                    it.copy(preferencesReadFailed = true, isLoading = false, results = emptyList())
                }
            }
        }
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // no optimistic persistence claims; retain exact mutation for Retry
    private fun mutate(mutation: Mutation) {
        // Only the separately confirmed clear-history command may recover unreadable visits.
        // Every other edit still requires successfully loaded preferences; never reset implicitly.
        if (!canAdmitMutation(mutation)) return
        val recoveryClear = mutation == Mutation.ClearVisits && _state.value.preferencesReadFailed
        if (_state.value.isSaving) {
            // The snackbar Undo remains actionable while a preference save is pending.
            // Keep explicit visit intents in order; other controls are disabled in the UI.
            if (mutation is Mutation.Visit) pendingWrites.addLast(PendingWrite(mutation, isRetry = false))
            return
        }
        _state.update { it.copy(isSaving = true, preferencesWriteFailed = false) }
        viewModelScope.launch {
            try {
                persist(mutation)
                mutationSaved(mutation, recoveryClear)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: VisitLimitReachedException) {
                // Capacity cannot improve by retrying the same write; do not evict or claim success.
                failedMutations.remove(mutation.writeKey())
                _state.update { it.copy(visitLimitReached = true) }
            } catch (_: Exception) {
                failedMutations[mutation.writeKey()] = mutation
                health.failure(PlacesSeam.PREFERENCES_WRITE)
                _state.update { it.copy(preferencesWriteFailed = true) }
            } finally {
                _state.update {
                    it.copy(isSaving = false, preferencesWriteFailed = failedMutations.isNotEmpty())
                }
                if (isActive) drainPendingWrite()
            }
        }
    }

    private fun canAdmitMutation(mutation: Mutation): Boolean =
        (mutation == Mutation.ClearVisits && _state.value.preferencesReadFailed) ||
            (!_state.value.preferencesReadFailed && preferences.value != null)

    private fun drainPendingWrite() {
        if (_state.value.isSaving) return
        while (pendingWrites.isNotEmpty()) {
            val next = pendingWrites.first()
            // A newer successful visit can supersede a queued Retry of an older clear.
            // Direct accepted intent must never depend on whether it previously failed.
            if (next.isRetry && failedMutations[next.mutation.writeKey()] != next.mutation) {
                pendingWrites.removeFirst()
                continue
            }
            // Admission may close mid-save; keep accepted intent until a read reopens it.
            if (!canAdmitMutation(next.mutation)) return
            pendingWrites.removeFirst()
            mutate(next.mutation)
            return
        }
    }

    private fun Mutation.writeKey(): String = when (this) {
        is Mutation.Interest -> "interest:${category.name}"
        is Mutation.NewFirst -> "new-first"
        is Mutation.Visit -> "visit:${result.placeKey()}"
        Mutation.ClearVisits -> "clear-visits"
    }

    private suspend fun persist(mutation: Mutation) {
        when (mutation) {
            is Mutation.Interest -> store.setInterest(mutation.category, mutation.enabled)
            is Mutation.NewFirst -> store.setPreferNew(mutation.enabled)
            is Mutation.Visit -> store.setVisited(mutation.result, mutation.visited)
            Mutation.ClearVisits -> store.clearVisits()
        }
    }

    private fun mutationSaved(mutation: Mutation, recoveryClear: Boolean) {
        failedMutations.remove(mutation.writeKey())
        health.success(PlacesSeam.PREFERENCES_WRITE)
        when (mutation) {
            is Mutation.Visit -> {
                // Clear admission removed older visit intents, and writes drain in order.
                // Only this SUCCESSFUL later history intent supersedes a failed clear;
                // a rejected, failed or cancelled visit must leave that clear retryable.
                failedMutations.remove(Mutation.ClearVisits.writeKey())
                _state.update {
                    it.copy(
                        visitNotice = VisitNotice(++noticeId, mutation.result, mutation.visited),
                        visitLimitReached = false,
                    )
                }
            }
            Mutation.ClearVisits -> {
                // New confirmation superseded older intent at admission. Retrying an
                // older clear must leave later failed visits retryable until they succeed.
                _state.update { it.copy(visitNotice = null, visitLimitReached = false) }
                if (recoveryClear) observePreferences()
            }
            else -> Unit
        }
    }

    private fun isCurrent(snapshot: Snapshot): Boolean =
        filters.value == snapshot.filters &&
            preferences.value == snapshot.preferences &&
            repository.generation.value == snapshot.revision.catalogGeneration &&
            refresh.value == snapshot.revision.requestSequence

    private data class Revision(val catalogGeneration: Long, val requestSequence: Long)

    private data class Snapshot(
        val filters: ExploreFilters,
        val availability: DiscoveryAvailability,
        val revision: Revision,
        val preferences: ExplorationPreferences?,
        val bundled: Boolean,
    )

    private data class PendingWrite(val mutation: Mutation, val isRetry: Boolean)

    private sealed interface Mutation {
        data class Interest(val category: ExploreCategory, val enabled: Boolean) : Mutation

        data class NewFirst(val enabled: Boolean) : Mutation

        data class Visit(val result: SearchResult, val visited: Boolean) : Mutation

        data object ClearVisits : Mutation
    }

    companion object {
        val RADII = listOf(5, 20, 50)
        private const val QUERY_DELAY_MS = 250L
    }
}
