/*
 * SettingsViewModel — drives the Settings screen.
 *
 * Exposes the current Settings as a StateFlow and write operations for each
 * toggle / selector. Follows the same pattern as PinListViewModel.
 */
package au.com.ausroads.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Settings(),
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setTheme(mode)
        }
    }

    fun setTrafficOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLiveTrafficEnabled(enabled)
        }
    }

    fun setShowAttribution(show: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowAttributionOverlay(show)
        }
    }

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTtsEnabled(enabled)
        }
    }

    fun setCongestionOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCongestionOverlayEnabled(enabled)
        }
    }

    fun setNswTrafficApiKey(key: String) {
        viewModelScope.launch {
            settingsRepository.setNswTrafficApiKey(key)
        }
    }

    fun setVicTrafficApiKey(key: String) {
        viewModelScope.launch {
            settingsRepository.setVicTrafficApiKey(key)
        }
    }

    fun setAvoidOptions(avoidTolls: Boolean, avoidUnsealed: Boolean, avoidFerries: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAvoidOptions(avoidTolls, avoidUnsealed, avoidFerries)
        }
    }
}
