package au.com.ausroads.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.ThemeMode
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Defaults are only an initial display fallback, never confirmation of a successful read. */
data class SettingsStoreState(
    val hasReadValue: Boolean = false,
    val readFailed: Boolean = false,
    val retryingRead: Boolean = false,
    val pendingWrites: Int = 0,
    val writeFailed: Boolean = false,
) {
    val canEdit: Boolean get() = hasReadValue && !readFailed
    val isSaving: Boolean get() = pendingWrites > 0
}

/** One Activity-owned coordinator; confirmed values alone drive theme and guidance consumers. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val health: PlacesHealth,
) : ViewModel() {
    private val confirmed = MutableStateFlow(Settings())
    val settings = confirmed.asStateFlow()
    private val status = MutableStateFlow(SettingsStoreState())
    val storeState = status.asStateFlow()
    private val nswDraft = MutableStateFlow<String?>(null)
    val nswApiDraft = nswDraft.asStateFlow()
    private val vicDraft = MutableStateFlow<String?>(null)
    val vicApiDraft = vicDraft.asStateFlow()
    private val readRetry = Channel<Unit>(Channel.CONFLATED)
    private val writer = SettingsWriter(viewModelScope, health, status)
    private var retryAfterRead = emptyList<SettingsIntent>()

    init {
        viewModelScope.launch {
            settingsRepository.settings.retryWhen { cause, _ ->
                if (cause is CancellationException || cause !is Exception) throw cause
                status.update { it.copy(readFailed = true, retryingRead = false) }
                health.failure(PlacesSeam.SETTINGS_READ)
                readRetry.receive()
                true
            }.collect { value ->
                confirmed.value = value
                status.update { it.copy(hasReadValue = true, readFailed = false, retryingRead = false) }
                health.success(PlacesSeam.SETTINGS_READ)
                val retry = retryAfterRead
                retryAfterRead = emptyList()
                writer.retry(retry)
            }
        }
    }

    fun retry() {
        if (status.value.readFailed && !status.value.retryingRead) {
            retryAfterRead = writer.failedIntents
            status.update { it.copy(retryingRead = true) }
            readRetry.trySend(Unit)
        }
        if (status.value.canEdit) writer.retry()
    }

    fun setThemeMode(mode: ThemeMode) = writer.save(Setting.THEME) { settingsRepository.setTheme(mode) }

    fun setTrafficOverlayEnabled(enabled: Boolean) =
        writer.save(Setting.TRAFFIC) { settingsRepository.setLiveTrafficEnabled(enabled) }

    fun setSpeedCamerasEnabled(enabled: Boolean) =
        writer.save(Setting.CAMERAS) { settingsRepository.setSpeedCamerasEnabled(enabled) }

    fun setShowAttribution(show: Boolean) =
        writer.save(Setting.ATTRIBUTION) { settingsRepository.setShowAttributionOverlay(show) }

    fun setTtsEnabled(enabled: Boolean) = writer.save(Setting.TTS) { settingsRepository.setTtsEnabled(enabled) }

    fun setCongestionOverlayEnabled(enabled: Boolean) =
        writer.save(Setting.CONGESTION) { settingsRepository.setCongestionOverlayEnabled(enabled) }

    fun setNswTrafficApiKey(key: String) {
        nswDraft.value = key // Own the latest editor text while confirmed emissions lag.
        writer.save(Setting.NSW_KEY) { settingsRepository.setNswTrafficApiKey(key) }
    }

    fun setVicTrafficApiKey(key: String) {
        vicDraft.value = key
        writer.save(Setting.VIC_KEY) { settingsRepository.setVicTrafficApiKey(key) }
    }

    fun setAvoidOptions(avoidTolls: Boolean, avoidUnsealed: Boolean, avoidFerries: Boolean) =
        writer.save(Setting.AVOID) { settingsRepository.setAvoidOptions(avoidTolls, avoidUnsealed, avoidFerries) }
}

private enum class Setting { THEME, TRAFFIC, CAMERAS, ATTRIBUTION, TTS, CONGESTION, NSW_KEY, VIC_KEY, AVOID }
private data class SettingsIntent(val setting: Setting, val serial: Long, val persist: suspend () -> Unit)

/** Serial, latest-per-concern persistence; the reader owns readiness, this owner owns retries. */
private class SettingsWriter(
    private val scope: CoroutineScope,
    private val health: PlacesHealth,
    private val status: MutableStateFlow<SettingsStoreState>,
) {
    private val mutex = Mutex()
    private var serial = 0L
    private val latest = mutableMapOf<Setting, Long>()
    private val failed = mutableMapOf<Setting, SettingsIntent>()
    val failedIntents get() = failed.values.sortedBy { it.serial }.toList()

    fun retry(intents: List<SettingsIntent> = failedIntents) {
        intents.filter { latest[it.setting] == it.serial && failed[it.setting]?.serial == it.serial }
            .forEach { save(it.setting, it.persist) }
    }

    fun save(setting: Setting, persist: suspend () -> Unit) {
        val intent = SettingsIntent(setting, ++serial, persist)
        latest[setting] = intent.serial
        failed.remove(setting) // A newer explicit intent supersedes this concern's old Retry.
        if (!status.value.canEdit) {
            failed[setting] = intent
            status.update { it.copy(writeFailed = true) }
            health.failure(PlacesSeam.SETTINGS_WRITE)
            return
        }
        scope.launch { performWrite(intent) }
    }

    @Suppress("TooGenericExceptionCaught") // A failed store is explicit, recoverable, and redacted.
    private suspend fun performWrite(intent: SettingsIntent) {
        status.update { it.copy(pendingWrites = it.pendingWrites + 1, writeFailed = failed.isNotEmpty()) }
        try {
            mutex.withLock {
                if (latest[intent.setting] != intent.serial) return@withLock
                intent.persist()
                if (latest[intent.setting] == intent.serial) failed.remove(intent.setting)
                health.success(PlacesSeam.SETTINGS_WRITE)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (latest[intent.setting] == intent.serial) failed[intent.setting] = intent
            health.failure(PlacesSeam.SETTINGS_WRITE)
        } finally {
            status.update { it.copy(pendingWrites = it.pendingWrites - 1, writeFailed = failed.isNotEmpty()) }
        }
    }
}
