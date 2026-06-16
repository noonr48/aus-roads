package au.com.ausroads.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.data.routes.RouteHistoryDao
import au.com.ausroads.data.routes.RouteHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import javax.inject.Inject

@HiltViewModel
class RouteHistoryViewModel @Inject constructor(
    private val routeHistoryDao: RouteHistoryDao,
) : ViewModel() {

    val recentRoutes: StateFlow<List<RouteHistoryEntity>> = routeHistoryDao
        .observeRecent(limit = 20)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

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
        viewModelScope.launch {
            routeHistoryDao.insert(
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
                )
            )
        }
    }

    fun deleteRoute(route: RouteHistoryEntity) {
        viewModelScope.launch {
            routeHistoryDao.deleteById(route.id)
        }
    }
}
