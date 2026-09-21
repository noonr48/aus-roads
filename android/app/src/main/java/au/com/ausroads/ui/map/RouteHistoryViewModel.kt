package au.com.ausroads.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.data.routes.RouteHistoryDao
import au.com.ausroads.data.routes.RouteHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class RouteHistoryViewModel @Inject constructor(
    private val routeHistoryDao: RouteHistoryDao,
) : ViewModel() {

    private val loadGeneration = MutableStateFlow(0)
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()
    private val _saveError = MutableStateFlow(false)
    val saveError: StateFlow<Boolean> = _saveError.asStateFlow()
    private var pendingSave: RouteHistoryEntity? = null

    val recentRoutes: StateFlow<List<RouteHistoryEntity>> = loadGeneration
        .flatMapLatest {
            routeHistoryDao.observeRecent(limit = 20)
                .onStart {
                    _isLoading.value = true
                    _loadError.value = false
                }
                .onEach {
                    _isLoading.value = false
                    _loadError.value = false
                }
                .catch { error ->
                    if (error is CancellationException) throw error
                    android.util.Log.e("RouteHistoryViewModel", "Loading recent routes failed", error)
                    _isLoading.value = false
                    _loadError.value = true
                    emit(emptyList())
                }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun retryLoad() {
        loadGeneration.update { it + 1 }
    }

    @Suppress("LongParameterList")
    fun saveRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        originName: String = "",
        destName: String = "",
        distanceMeters: Int,
        durationSeconds: Int,
    ) {
        persistRoute(
            RouteHistoryEntity(
                originLat = originLat,
                originLon = originLon,
                destLat = destLat,
                destLon = destLon,
                originName = originName,
                destName = destName,
                distanceMeters = distanceMeters,
                durationSeconds = durationSeconds,
                createdAt = Clock.System.now(),
            ),
        )
    }

    fun retrySave() {
        pendingSave?.let(::persistRoute)
    }

    fun dismissSaveError() {
        pendingSave = null
        _saveError.value = false
    }

    @Suppress("TooGenericExceptionCaught") // Room may surface storage and SQLite failures.
    private fun persistRoute(route: RouteHistoryEntity) {
        pendingSave = route
        _saveError.value = false
        viewModelScope.launch {
            try {
                routeHistoryDao.insert(route)
                if (pendingSave == route) {
                    pendingSave = null
                    _saveError.value = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("RouteHistoryViewModel", "Saving route history failed", error)
                if (pendingSave == route) _saveError.value = true
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Room can surface multiple storage exceptions.
    fun deleteRoute(
        route: RouteHistoryEntity,
        onDeleted: () -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                check(routeHistoryDao.deleteById(route.id) == 1) {
                    "Route history entry ${route.id} no longer exists"
                }
                onDeleted()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error)
            }
        }
    }
}
