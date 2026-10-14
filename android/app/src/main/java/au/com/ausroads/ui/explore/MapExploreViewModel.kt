package au.com.ausroads.ui.explore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.offline.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One activity-owned map area and a one-shot internal selection crossing; never starts a route. */
@HiltViewModel
class MapExploreViewModel
@Inject
constructor(
    private val savedState: SavedStateHandle,
    private val health: PlacesHealth,
) : ViewModel() {
    private val initial =
        savedState.get<DoubleArray>("explore_map_center")?.let {
            if (validSavedPoint(it)) GeoPoint(longitude = it[1], latitude = it[0]) else null
        } ?: GeoPoint(longitude = 138.62, latitude = -34.92)
    private val _reference = MutableStateFlow(initial)
    val reference = _reference.asStateFlow()
    private val _selection = MutableStateFlow<SearchResult?>(null)
    val selection = _selection.asStateFlow()

    private fun validSavedPoint(values: DoubleArray): Boolean =
        values.size == 2 && validLatitude(values[0]) && validLongitude(values[1])

    private fun validLatitude(value: Double) = value.isFinite() && value in -90.0..90.0

    private fun validLongitude(value: Double) = value.isFinite() && value in -180.0..180.0

    fun updateCenter(latitude: Double, longitude: Double) {
        val point = GeoPoint(longitude = longitude, latitude = latitude)
        _reference.value = point
        savedState["explore_map_center"] = doubleArrayOf(latitude, longitude)
    }

    fun openOnMap(result: SearchResult) {
        // Validate the same named longitude/latitude contract before navigation.
        GeoPoint(longitude = result.longitude, latitude = result.latitude)
        _selection.value = result
    }

    fun consumeSelection(result: SearchResult) {
        if (_selection.value == result) {
            _selection.value = null
            health.success(PlacesSeam.MAP_SELECTION)
        }
    }
}
