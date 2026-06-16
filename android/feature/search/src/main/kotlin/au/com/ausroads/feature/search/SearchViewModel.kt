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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    init {
        _query
            .debounce(300)
            .distinctUntilChanged()
            .onEach { q ->
                if (q.isBlank()) {
                    _isSearching.update { false }
                    _searchFailed.update { false }
                    _results.update { emptyList() }
                } else {
                    _isSearching.update { true }
                    _searchFailed.update { false }
                    try {
                        val found = searchRepository.search(q, limit = 20)
                        _results.update { found }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A corrupt/missing index must not kill the search collector
                        // or crash the app — surface an error state instead.
                        _results.update { emptyList() }
                        _searchFailed.update { true }
                    } finally {
                        _isSearching.update { false }
                    }
                }
            }
            .catch { /* upstream backstop: never let the collector die */ }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(newQuery: String) {
        _query.update { newQuery }
    }

    fun onActiveChange(active: Boolean) {
        _isActive.update { active }
    }

    fun onResultSelected(result: SearchResult) {
        _isActive.update { false }
    }

    fun onClear() {
        _query.update { "" }
        _results.update { emptyList() }
        _isSearching.update { false }
        _searchFailed.update { false }
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
