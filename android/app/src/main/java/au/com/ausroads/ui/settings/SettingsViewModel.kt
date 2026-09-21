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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SettingsField {
    Theme,
    LiveTraffic,
    Attribution,
    TextToSpeech,
    Congestion,
    NswTrafficApiKey,
    VicTrafficApiKey,
    RoutePreferences,
}

data class SettingsWriteError(
    val setting: SettingsField,
    val isRetrying: Boolean = false,
)

internal sealed interface SettingsMutation {
    val setting: SettingsField
    suspend fun persist(repository: SettingsRepository)

    data class Theme(val mode: ThemeMode) : SettingsMutation {
        override val setting = SettingsField.Theme
        override suspend fun persist(repository: SettingsRepository) = repository.setTheme(mode)
    }

    data class LiveTraffic(val enabled: Boolean) : SettingsMutation {
        override val setting = SettingsField.LiveTraffic
        override suspend fun persist(repository: SettingsRepository) =
            repository.setLiveTrafficEnabled(enabled)
    }

    data class Attribution(val show: Boolean) : SettingsMutation {
        override val setting = SettingsField.Attribution
        override suspend fun persist(repository: SettingsRepository) =
            repository.setShowAttributionOverlay(show)
    }

    data class TextToSpeech(val enabled: Boolean) : SettingsMutation {
        override val setting = SettingsField.TextToSpeech
        override suspend fun persist(repository: SettingsRepository) = repository.setTtsEnabled(enabled)
    }

    data class Congestion(val enabled: Boolean) : SettingsMutation {
        override val setting = SettingsField.Congestion
        override suspend fun persist(repository: SettingsRepository) =
            repository.setCongestionOverlayEnabled(enabled)
    }

    data class NswTrafficApiKey(val key: String) : SettingsMutation {
        override val setting = SettingsField.NswTrafficApiKey
        override suspend fun persist(repository: SettingsRepository) = repository.setNswTrafficApiKey(key)
    }

    data class VicTrafficApiKey(val key: String) : SettingsMutation {
        override val setting = SettingsField.VicTrafficApiKey
        override suspend fun persist(repository: SettingsRepository) = repository.setVicTrafficApiKey(key)
    }

    data class RoutePreferences(
        val avoidTolls: Boolean,
        val avoidUnsealed: Boolean,
        val avoidFerries: Boolean,
    ) : SettingsMutation {
        override val setting = SettingsField.RoutePreferences

        override suspend fun persist(repository: SettingsRepository) = repository.setAvoidOptions(
            avoidTolls = avoidTolls,
            avoidUnsealed = avoidUnsealed,
            avoidFerries = avoidFerries,
        )
    }
}

// The public setters intentionally mirror the independently persisted settings;
// keeping them named makes Compose callbacks and failure labels unambiguous.
@Suppress("TooManyFunctions")
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private data class MutationRequest(
        val id: Long,
        val mutation: SettingsMutation,
    )

    private val _writeError = MutableStateFlow<SettingsWriteError?>(null)
    val writeError: StateFlow<SettingsWriteError?> = _writeError.asStateFlow()

    private var nextMutationId = 0L
    private val latestMutationIds = mutableMapOf<SettingsField, Long>()
    private var failedMutation: SettingsMutation? = null

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Settings(),
        )

    fun setThemeMode(mode: ThemeMode) {
        submit(SettingsMutation.Theme(mode))
    }

    fun setTrafficOverlayEnabled(enabled: Boolean) {
        submit(SettingsMutation.LiveTraffic(enabled))
    }

    fun setShowAttribution(show: Boolean) {
        submit(SettingsMutation.Attribution(show))
    }

    fun setTtsEnabled(enabled: Boolean) {
        submit(SettingsMutation.TextToSpeech(enabled))
    }

    fun setCongestionOverlayEnabled(enabled: Boolean) {
        submit(SettingsMutation.Congestion(enabled))
    }

    fun setNswTrafficApiKey(key: String) {
        submit(SettingsMutation.NswTrafficApiKey(key))
    }

    fun setVicTrafficApiKey(key: String) {
        submit(SettingsMutation.VicTrafficApiKey(key))
    }

    fun setAvoidOptions(avoidTolls: Boolean, avoidUnsealed: Boolean, avoidFerries: Boolean) {
        submit(
            SettingsMutation.RoutePreferences(
                avoidTolls = avoidTolls,
                avoidUnsealed = avoidUnsealed,
                avoidFerries = avoidFerries,
            ),
        )
    }

    fun retryFailedWrite() {
        val error = _writeError.value ?: return
        val mutation = failedMutation ?: return
        if (error.isRetrying) return

        _writeError.value = error.copy(isRetrying = true)
        submit(mutation)
    }

    fun dismissWriteError() {
        failedMutation?.setting?.let { setting ->
            latestMutationIds[setting] = ++nextMutationId
        }
        failedMutation = null
        _writeError.value = null
    }

    private fun submit(mutation: SettingsMutation) {
        val request = register(mutation)
        viewModelScope.launch { persist(request) }
    }

    internal suspend fun persistMutation(mutation: SettingsMutation) {
        persist(register(mutation))
    }

    private fun register(mutation: SettingsMutation): MutationRequest {
        val request = MutationRequest(id = ++nextMutationId, mutation = mutation)
        latestMutationIds[mutation.setting] = request.id
        return request
    }

    private suspend fun persist(request: MutationRequest) {
        try {
            request.mutation.persist(settingsRepository)
            handleSuccess(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            handleFailure(request)
        }
    }

    private fun handleSuccess(request: MutationRequest) {
        val setting = request.mutation.setting
        if (latestMutationIds[setting] != request.id) return
        if (failedMutation?.setting == setting) {
            failedMutation = null
            _writeError.value = null
        }
    }

    private fun handleFailure(request: MutationRequest) {
        val mutation = request.mutation
        if (latestMutationIds[mutation.setting] != request.id) return
        failedMutation = mutation
        _writeError.value = SettingsWriteError(setting = mutation.setting)
    }
}
