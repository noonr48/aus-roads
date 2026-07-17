package au.com.ausroads.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchFailed = MutableStateFlow(false)
    val searchFailed: StateFlow<Boolean> = _searchFailed.asStateFlow()

    private val _searchUnavailable = MutableStateFlow(false)
    val searchUnavailable: StateFlow<Boolean> = _searchUnavailable.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    init {
        viewModelScope.launch {
            _query
                .debounce(300)
                .distinctUntilChanged()
                .combine(searchRepository.readiness.distinctUntilChanged()) { query, ready ->
                    query to ready
                }
                .catch {
                    _isSearching.update { false }
                    _results.update { emptyList() }
                    _searchFailed.update { true }
                }
                .collectLatest { (q, ready) ->
                    // The debounced value can lag behind text the user has already
                    // entered. Never let that old emission overwrite the immediate
                    // pending state established by onQueryChange().
                    if (q != _query.value) return@collectLatest
                    if (q.isBlank()) {
                        _isSearching.update { false }
                        _searchFailed.update { false }
                        _searchUnavailable.update { false }
                        _results.update { emptyList() }
                    } else {
                        if (!ready) {
                            _isSearching.update { false }
                            _searchFailed.update { false }
                            _searchUnavailable.update { true }
                            _results.update { emptyList() }
                            return@collectLatest
                        }
                        _isSearching.update { true }
                        _searchFailed.update { false }
                        _searchUnavailable.update { false }
                        try {
                            val found = searchRepository.search(q, limit = 20)
                            if (q == _query.value) _results.update { found }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A corrupt/missing index must not kill the search collector
                            // or crash the app — surface an error state instead.
                            if (q == _query.value) {
                                _results.update { emptyList() }
                                _searchFailed.update { true }
                            }
                        } finally {
                            if (q == _query.value) _isSearching.update { false }
                        }
                    }
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        if (newQuery == _query.value) return
        _query.update { newQuery }
        _results.update { emptyList() }
        _isSearching.update { newQuery.isNotBlank() }
        _searchFailed.update { false }
        _searchUnavailable.update { false }
    }

    fun onActiveChange(active: Boolean) {
        _isActive.update { active }
    }

    @Suppress("UnusedParameter")
    fun onResultSelected(result: SearchResult) {
        _isActive.update { false }
        onClear()
    }

    fun onClear() {
        _query.update { "" }
        _results.update { emptyList() }
        _isSearching.update { false }
        _searchFailed.update { false }
        _searchUnavailable.update { false }
    }

    /**
     * Offline reverse-geocode: best-effort human-readable label for a coordinate.
     * Prefers the nearest suburb, falling back to the nearest indexed feature of
     * any kind. Returns null when nothing is indexed nearby.
     */
    suspend fun nearestPlaceName(latitude: Double, longitude: Double): String? {
        val suburb = searchRepository.nearest(latitude, longitude, kind = "suburb")
        val place = suburb ?: searchRepository.nearest(latitude, longitude)
        return place?.name
    }
}
