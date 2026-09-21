package au.com.ausroads.feature.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.traffic.provider.LiveTrafficProvider
import au.com.ausroads.traffic.provider.TrafficProviderAuthenticationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val _error = MutableStateFlow<TrafficFailure?>(null)
    val error: StateFlow<TrafficFailure?> = _error.asStateFlow()

    private val etags = ConcurrentHashMap<String, String?>()
    private val closureEtags = ConcurrentHashMap<String, String?>()
    private val providerSnapshots = mutableMapOf<String, ProviderTrafficSnapshot>()
    private val fetchMutex = Mutex()
    private var pollingJob: Job? = null
    private var refreshJob: Job? = null

    /**
     * Start polling for traffic updates. Called from the UI when the map screen
     * is visible. Stops automatically when the ViewModel is cleared.
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (true) {
                fetchTrafficNow()
                delay(POLL_INTERVAL)
            }
        }
    }

    /** Stop both the polling loop and any explicit refresh currently in flight. */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        refreshJob?.cancel()
        refreshJob = null
        _isLoading.update { false }
    }

    fun fetchTraffic() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            fetchTrafficNow()
        }
    }

    private suspend fun fetchTrafficNow() {
        fetchMutex.withLock {
            _isLoading.update { true }
            _error.update { null }
            try {
                val allEvents = mutableListOf<LiveTrafficEvent>()
                var lastError: Exception? = null
                var successfulProviderCount = 0
                val invalidCredentialRegions = mutableSetOf<String>()
                for (provider in providers) {
                    try {
                        val eventsResult = provider.fetchEvents(bbox = null, ifNoneMatch = etags[provider.regionCode])
                        val closuresResult = provider.fetchClosures(bbox = null, ifNoneMatch = closureEtags[provider.regionCode])
                        if (eventsResult.etag != null) etags[provider.regionCode] = eventsResult.etag
                        if (closuresResult.etag != null) closureEtags[provider.regionCode] = closuresResult.etag
                        val previous = providerSnapshots[provider.regionCode] ?: ProviderTrafficSnapshot()
                        val snapshot = ProviderTrafficSnapshot(
                            events = if (eventsResult.notModified) previous.events else eventsResult.events,
                            closures = if (closuresResult.notModified) previous.closures else closuresResult.events,
                        )
                        providerSnapshots[provider.regionCode] = snapshot
                        allEvents.addAll(snapshot.events + snapshot.closures)
                        successfulProviderCount += 1
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: TrafficProviderAuthenticationException) {
                        lastError = e
                        invalidCredentialRegions += e.regionCode
                        providerSnapshots[provider.regionCode]?.let { previous ->
                            allEvents.addAll(previous.events + previous.closures)
                        }
                    } catch (e: Exception) {
                        lastError = e
                        providerSnapshots[provider.regionCode]?.let { previous ->
                            allEvents.addAll(previous.events + previous.closures)
                        }
                    }
                }
                val failure = lastError
                _error.update {
                    when {
                        invalidCredentialRegions.isNotEmpty() -> TrafficFailure.InvalidCredentials(
                            regionCode = invalidCredentialRegions.sorted().first(),
                        )
                        successfulProviderCount == 0 && failure != null ->
                            TrafficFailure.AllProvidersUnavailable
                        else -> null
                    }
                }
                _events.update { allEvents }
                if (successfulProviderCount > 0) {
                    _lastUpdated.update { Clock.System.now() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.update { TrafficFailure.AllProvidersUnavailable }
            } finally {
                _isLoading.update { false }
            }
        }
    }

    companion object {
        val POLL_INTERVAL = 5.minutes
    }
}

private data class ProviderTrafficSnapshot(
    val events: List<LiveTrafficEvent> = emptyList(),
    val closures: List<LiveTrafficEvent> = emptyList(),
)

sealed interface TrafficFailure {
    data object AllProvidersUnavailable : TrafficFailure

    data class InvalidCredentials(
        val regionCode: String,
    ) : TrafficFailure
}
