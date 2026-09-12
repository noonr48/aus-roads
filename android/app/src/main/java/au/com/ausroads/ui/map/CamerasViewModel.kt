package au.com.ausroads.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SpeedCameraPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cameras visible on the map, plus the tapped-camera selection. */
data class CamerasUiState(
    val cameras: List<SpeedCameraPoint> = emptyList(),
    val selected: SpeedCameraPoint? = null,
)

/**
 * Viewport-driven speed-camera layer: whenever the map comes to rest, query the
 * pack's `road_cameras` table for the visible area (debounced, so pan/zoom
 * gestures do not spam the database).
 *
 * The first query also runs at map-ready with the default camera so Adelaide
 * shows its cameras before the user pans at all.
 */
@HiltViewModel
class CamerasViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CamerasUiState())
    val state: StateFlow<CamerasUiState> = _state.asStateFlow()

    private var queryJob: Job? = null

    /** Map came to rest (or is ready): refresh cameras around the viewport. */
    fun onViewportChanged(centerLatitude: Double, centerLongitude: Double, halfSpanMeters: Double) {
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(VIEWPORT_DEBOUNCE_MS)
            val radiusDegrees = (halfSpanMeters / METERS_PER_DEGREE_LATITUDE)
                .coerceIn(MIN_RADIUS_DEGREES, MAX_RADIUS_DEGREES)
            val cameras = try {
                searchRepository.camerasNear(
                    latitude = centerLatitude,
                    longitude = centerLongitude,
                    maxDistanceDegrees = radiusDegrees,
                    limit = CAMERA_LIMIT,
                )
            } catch (e: CancellationException) {
                // A cancelled query (rapid pan → debounce superseded) must not
                // publish an empty layer over a live one.
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            _state.update { it.copy(cameras = cameras) }
        }
    }

    /** A camera marker was tapped; null clears the detail sheet. */
    fun select(camera: SpeedCameraPoint?) {
        _state.update { it.copy(selected = camera) }
    }

    override fun onCleared() {
        queryJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Debounce for pan/zoom bursts; keeps queries to rest-state only. */
        const val VIEWPORT_DEBOUNCE_MS = 250L

        /** Metres per degree of latitude (mean Earth approximation). */
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0

        /** Floor ~1 km of arc (city block scale). */
        const val MIN_RADIUS_DEGREES = 0.01

        /** Ceiling ~40 km of arc (state-wide zoom still bounded). */
        const val MAX_RADIUS_DEGREES = 0.36

        /** Rendered marker cap; nearest-first keeps the relevant ones. */
        const val CAMERA_LIMIT = 150
    }
}
