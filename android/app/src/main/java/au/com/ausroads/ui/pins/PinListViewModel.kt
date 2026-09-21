/*
 * PinListViewModel — v0.1.1.
 *
 * Drives the Pins screen. Exposes explicit loading, content, and read-error
 * states plus write operations (add / rename / delete) used by the UI and the
 * MapScreen long-press handler.
 *
 * Dependencies: PinRepository / Pin from the :data:pins module, injected via Hilt.
 */
package au.com.ausroads.ui.pins

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.data.pins.Pin
import au.com.ausroads.data.pins.PinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

sealed interface PinListUiState {
    data object Loading : PinListUiState
    data class Content(val pins: List<Pin>) : PinListUiState
    data object ReadError : PinListUiState
}

@HiltViewModel
class PinListViewModel @Inject constructor(
    private val pinRepository: PinRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PinListUiState>(PinListUiState.Loading)
    val uiState: StateFlow<PinListUiState> = _uiState.asStateFlow()

    private var observationJob: Job? = null

    init {
        subscribeToPins()
    }

    fun retrySavedPins() {
        subscribeToPins()
    }

    @Suppress("TooGenericExceptionCaught") // Room can surface several read failures
    private fun subscribeToPins() {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            _uiState.value = PinListUiState.Loading
            try {
                pinRepository.observeAll().collect { pins ->
                    _uiState.value = PinListUiState.Content(pins)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to load saved pins", error)
                _uiState.value = PinListUiState.ReadError
            }
        }
    }

    /**
     * Add a pin at the given coordinates. The future MapScreen will call this
     * from its long-press handler. A fire-and-forget `viewModelScope.launch`
     * is fine here — the StateFlow re-emits on Room write, so the UI updates
     * without blocking the caller.
     */
    @Suppress("LongParameterList", "TooGenericExceptionCaught") // Room can surface several write failures
    fun addPinAt(
        latitude: Double,
        longitude: Double,
        name: String? = null,
        color: String = "#1B5E20",
        onSaved: ((Pin) -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            try {
                require(areValidPinCoordinates(latitude, longitude)) {
                    "Pin coordinates are outside world bounds"
                }
                val pin = Pin(
                    name = name?.takeIf { it.isNotBlank() } ?: defaultName(latitude, longitude),
                    lat = latitude,
                    lon = longitude,
                    color = color,
                    createdAt = Clock.System.now(),
                )
                val id = pinRepository.add(pin)
                onSaved?.invoke(pin.copy(id = id))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to save pin", error)
                onError?.invoke(error)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Room can surface several write failures
    fun updatePin(
        pin: Pin,
        newName: String,
        newColor: String,
        onUpdated: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            try {
                require(newName.isNotBlank()) { "Pin name cannot be blank" }
                check(pinRepository.update(pin.copy(name = newName, color = newColor)) == 1) {
                    "Saved pin no longer exists"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to update saved pin", error)
                onError?.invoke(error)
                return@launch
            }
            onUpdated?.invoke()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Room can surface several write failures
    fun delete(
        pin: Pin,
        onDeleted: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            try {
                check(pinRepository.delete(pin) == 1) { "Saved pin no longer exists" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to delete saved pin", error)
                onError?.invoke(error)
                return@launch
            }
            onDeleted?.invoke()
        }
    }

    private fun defaultName(lat: Double, lon: Double): String {
        // v0.1.1: just lat/lon. v0.1.2: reverse-geocode to suburb.
        @Suppress("MagicNumber")
        return PIN_NAME_FORMAT.format(lat, lon)
    }

    private companion object {
        private const val TAG = "PinListViewModel"
        private const val PIN_NAME_FORMAT = "Pin (%.4f, %.4f)"
    }
}
