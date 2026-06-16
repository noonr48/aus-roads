package au.com.ausroads.feature.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.traffic.provider.LiveTrafficProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import java.util.concurrent.ConcurrentHashMap

@HiltViewModel
class TrafficViewModel @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards LiveTrafficProvider>,
) : ViewModel() {

    private val _events = MutableStateFlow<List<LiveTrafficEvent>>(emptyList())
    val events: StateFlow<List<LiveTrafficEvent>> = _events.asStateFlow()

    private val _lastUpdated = MutableStateFlow<Instant?>(null)
    val lastUpdated: StateFlow<Instant?> = _lastUpdated.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val etags = ConcurrentHashMap<String, String?>()
    private val closureEtags = ConcurrentHashMap<String, String?>()
    private var pollingJob: Job? = null

    /**
     * Start polling for traffic updates. Called from the UI when the map screen
     * is visible. Stops automatically when the ViewModel is cleared.
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (true) {
                fetchTraffic()
                delay(POLL_INTERVAL)
            }
        }
    }

    fun fetchTraffic() {
        viewModelScope.launch {
            _isLoading.update { true }
            _error.update { null }
            try {
                val allEvents = mutableListOf<LiveTrafficEvent>()
                var lastError: Exception? = null
                for (provider in providers) {
                    try {
                        val eventsResult = provider.fetchEvents(bbox = null, ifNoneMatch = etags[provider.regionCode])
                        val closuresResult = provider.fetchClosures(bbox = null, ifNoneMatch = closureEtags[provider.regionCode])
                        if (eventsResult.etag != null) etags[provider.regionCode] = eventsResult.etag
                        if (closuresResult.etag != null) closureEtags[provider.regionCode] = closuresResult.etag
                        allEvents.addAll(eventsResult.events + closuresResult.events)
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                val failure = lastError
                if (allEvents.isEmpty() && failure != null) {
                    _error.update { failure.message }
                }
                _events.update { allEvents }
                _lastUpdated.update { Clock.System.now() }
            } catch (e: Exception) {
                _error.update { e.message }
            } finally {
                _isLoading.update { false }
            }
        }
    }

    companion object {
        val POLL_INTERVAL = 5.minutes
    }
}
