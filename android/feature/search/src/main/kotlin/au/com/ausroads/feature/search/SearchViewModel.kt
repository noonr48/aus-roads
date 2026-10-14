package au.com.ausroads.feature.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.offline.search.PlaceDiscoveryRepository
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
@Inject
constructor(private val searchRepository: SearchRepository, private val health: SearchHealth) :
    ViewModel() {
    private val _query = MutableStateFlow("")
    private val edits = MutableStateFlow(QueryEdit("", 0L))
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()
    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()
    private val _searchFailed = MutableStateFlow(false)
    val searchFailed: StateFlow<Boolean> = _searchFailed.asStateFlow()
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    private val generation =
        (searchRepository as? PlaceDiscoveryRepository)?.generation ?: MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            combine(edits.debounce(QUERY_DELAY_MS), generation) { edit, epoch -> edit to epoch }
                .collectLatest { (edit, epoch) ->
                    val q = edit.query
                    if (edit != edits.value) return@collectLatest
                    if (q.isBlank()) {
                        _isSearching.value = false
                        _searchFailed.value = false
                        _results.value = emptyList()
                        return@collectLatest
                    }
                    _isSearching.value = true
                    _searchFailed.value = false
                    try {
                        val found = searchRepository.search(q, limit = 20)
                        coroutineContext.ensureActive()
                        if (isCurrent(edit, epoch)) {
                            _results.value = found
                            health.success()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (isCurrent(edit, epoch)) {
                            _results.value = emptyList()
                            _searchFailed.value = true
                            health.failure()
                        }
                    } finally {
                        if (isCurrent(edit, epoch)) _isSearching.value = false
                    }
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        // StateFlow already deduplicates identical events. Do not clear their completed results.
        // No downstream distinct after debounce: A→B→A must replay the returned A query.
        if (newQuery == _query.value) return
        _query.value = newQuery
        edits.value = QueryEdit(newQuery, edits.value.serial + 1L)
        _results.value = emptyList()
        _searchFailed.value = false
        _isSearching.value = newQuery.isNotBlank()
    }

    /** Explicit recovery repeats the exact query with a new request identity, not a fake edit. */
    fun retry() {
        if (_query.value.isBlank() || _isSearching.value) return
        edits.value = QueryEdit(_query.value, edits.value.serial + 1L)
        _results.value = emptyList()
        _searchFailed.value = false
        _isSearching.value = true
    }

    fun onActiveChange(active: Boolean) {
        _isActive.value = active
    }

    fun onResultSelected(result: SearchResult) {
        onClear()
        _isActive.value = false
    }

    fun onClear() {
        _query.value = ""
        edits.value = QueryEdit("", edits.value.serial + 1L)
        _results.value = emptyList()
        _isSearching.value = false
        _searchFailed.value = false
    }

    /** Best-effort offline label; a failed index cannot crash a dropped-pin editor. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun nearestPlaceName(latitude: Double, longitude: Double): String? =
        try {
            val suburb = searchRepository.nearest(latitude, longitude, kind = "suburb")
            (suburb ?: searchRepository.nearest(latitude, longitude))?.name
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w("SearchViewModel", "component=places seam=reverse-label class=not-joined")
            null
        }

    private data class QueryEdit(val query: String, val serial: Long)

    private fun isCurrent(edit: QueryEdit, epoch: Long) =
        edit == edits.value && epoch == generation.value

    companion object {
        private const val QUERY_DELAY_MS = 300L
    }
}
