package au.com.ausroads.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.PlaceDiscoveryRepository
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SpeedCameraPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Cameras visible on the map, plus the tapped-camera selection. */
data class CamerasUiState(
    val cameras: List<SpeedCameraPoint> = emptyList(),
    val selected: SpeedCameraPoint? = null,
    val queryFailed: Boolean = false,
)

/** The last viewport refreshes when the catalog becomes ready or is replaced, without a pan. */
@HiltViewModel
class CamerasViewModel
@Inject
constructor(
    private val searchRepository: SearchRepository,
    private val health: PlacesHealth,
) : ViewModel() {
    private val _state = MutableStateFlow(CamerasUiState())
    val state = _state.asStateFlow()
    private val viewport = MutableStateFlow<Viewport?>(null)
    private val refresh = MutableStateFlow(0L)
    private val discovery = searchRepository as? PlaceDiscoveryRepository
    private val availability =
        discovery?.availability ?: MutableStateFlow(DiscoveryAvailability.READY)
    private val generation = discovery?.generation ?: MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            combine(viewport, availability, generation, refresh) { area, ready, epoch, serial ->
                    Request(area, ready, epoch, serial)
                }
                .collectLatest { request ->
                    val area = request.area
                    _state.update { it.copy(queryFailed = false) }
                    if (area == null || request.availability != DiscoveryAvailability.READY) {
                        _state.update { it.copy(cameras = emptyList(), selected = null) }
                        return@collectLatest
                    }
                    delay(VIEWPORT_DEBOUNCE_MS)
                    val cameras =
                        try {
                            searchRepository.camerasNear(
                                latitude = area.latitude,
                                longitude = area.longitude,
                                maxDistanceDegrees =
                                    (area.halfSpanMeters / METERS_PER_DEGREE_LATITUDE).coerceIn(
                                        MIN_RADIUS_DEGREES,
                                        MAX_RADIUS_DEGREES,
                                    ),
                                limit = CAMERA_LIMIT,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (isCurrent(request)) {
                                health.failure(PlacesSeam.CAMERAS_QUERY)
                                _state.update { it.copy(cameras = emptyList(), selected = null, queryFailed = true) }
                            }
                            return@collectLatest
                        }
                    coroutineContext.ensureActive()
                    if (isCurrent(request)) {
                        health.success(PlacesSeam.CAMERAS_QUERY)
                        _state.update { current ->
                            current.copy(
                                cameras = cameras,
                                selected = current.selected?.takeIf { it in cameras },
                                queryFailed = false,
                            )
                        }
                    }
                }
        }
    }

    /** Map came to rest (or is ready); bursts supersede work rather than publish stale layers. */
    fun onViewportChanged(centerLatitude: Double, centerLongitude: Double, halfSpanMeters: Double) {
        val next = Viewport(centerLatitude, centerLongitude, halfSpanMeters)
        if (viewport.value == next && _state.value.queryFailed) {
            // Consume one Retry immediately; duplicate idle callbacks cannot keep
            // canceling the same recovery while it is debounced/in flight.
            _state.update { it.copy(queryFailed = false) }
            refresh.value += 1L
        } else viewport.value = next
    }

    fun retry() {
        viewport.value?.let { onViewportChanged(it.latitude, it.longitude, it.halfSpanMeters) }
    }

    fun select(camera: SpeedCameraPoint?) {
        _state.update { it.copy(selected = camera) }
    }

    private fun isCurrent(request: Request) =
        viewport.value == request.area &&
            generation.value == request.generation &&
            refresh.value == request.serial &&
            availability.value == DiscoveryAvailability.READY

    private data class Viewport(
        val latitude: Double,
        val longitude: Double,
        val halfSpanMeters: Double,
    )

    private data class Request(
        val area: Viewport?,
        val availability: DiscoveryAvailability,
        val generation: Long,
        val serial: Long,
    )

    private companion object {
        const val VIEWPORT_DEBOUNCE_MS = 250L
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
        const val MIN_RADIUS_DEGREES = 0.01
        const val MAX_RADIUS_DEGREES = 0.36
        const val CAMERA_LIMIT = 150
    }
}
