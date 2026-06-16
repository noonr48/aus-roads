/*
 * PinListViewModel — v0.1.1.
 *
 * Drives the Pins screen. Exposes a hot StateFlow of all saved pins and a small
 * set of write operations (add / rename / delete) that the UI and the
 * MapScreen long-press handler can call.
 *
 * Dependencies: PinRepository / Pin from the :data:pins module, injected via Hilt.
 */
package au.com.ausroads.ui.pins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.data.pins.Pin
import au.com.ausroads.data.pins.PinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

@HiltViewModel
class PinListViewModel @Inject constructor(
    private val pinRepository: PinRepository,
) : ViewModel() {

    val pins: StateFlow<List<Pin>> = pinRepository.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * Add a pin at the given coordinates. The future MapScreen will call this
     * from its long-press handler. A fire-and-forget `viewModelScope.launch`
     * is fine here — the StateFlow re-emits on Room write, so the UI updates
     * without blocking the caller.
     */
    fun addPinAt(
        latitude: Double,
        longitude: Double,
        name: String? = null,
        color: String = "#1B5E20",
        onSaved: ((Pin) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val pin = Pin(
                name = name?.takeIf { it.isNotBlank() } ?: defaultName(latitude, longitude),
                lat = latitude,
                lon = longitude,
                color = color,
                createdAt = Clock.System.now(),
            )
            val id = pinRepository.add(pin)
            onSaved?.invoke(pin.copy(id = id))
        }
    }

    fun updatePin(pin: Pin, newName: String, newColor: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            pinRepository.update(pin.copy(name = newName, color = newColor))
        }
    }

    fun delete(pin: Pin) {
        viewModelScope.launch {
            pinRepository.delete(pin)
        }
    }

    private fun defaultName(lat: Double, lon: Double): String {
        // v0.1.1: just lat/lon. v0.1.2: reverse-geocode to suburb.
        @Suppress("MagicNumber")
        return PIN_NAME_FORMAT.format(lat, lon)
    }

    private companion object {
        private const val PIN_NAME_FORMAT = "Pin (%.4f, %.4f)"
    }
}
