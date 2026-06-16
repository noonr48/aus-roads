/*
 * NearbyViewModel — drives the "Nearby" screen.
 *
 * Holds a reference coordinate (defaulting to the Adelaide CBD) and, on demand,
 * queries the offline search pack for the nearest POIs of a chosen category,
 * computing the great-circle distance from the reference to each hit and
 * sorting nearest-first. It also eagerly loads a small "emergency" summary
 * (nearest hospital + nearest police) so that information is always on screen.
 *
 * Dependencies: SearchRepository (:offline:search), GeoPoint (:core:model),
 * MeasureGeometry (:core:geo) — all already on the :app classpath. Injected via
 * Hilt.
 *
 * The heavy per-result distance mapping/sorting runs off the main thread on a
 * [workDispatcher] that defaults to [Dispatchers.Default]. The dispatcher is a
 * settable property rather than a constructor parameter so production needs no
 * extra Hilt binding while unit tests can substitute a StandardTestDispatcher to
 * make the work deterministic under runTest/advanceUntilIdle.
 */
package au.com.ausroads.ui.nearby

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.core.geo.MeasureGeometry
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single POI hit paired with its great-circle distance from the reference. */
data class NearbyResult(
    val result: SearchResult,
    val distanceMeters: Double,
)

/** Nearest emergency services relative to the reference point. */
data class EmergencyInfo(
    val hospital: NearbyResult?,
    val police: NearbyResult?,
)

/** Immutable UI state for the Nearby screen. */
data class NearbyUiState(
    val categories: List<PoiCategory> = PoiCategory.entries.toList(),
    val selected: PoiCategory? = null,
    val results: List<NearbyResult> = emptyList(),
    val isLoading: Boolean = false,
    val emergency: EmergencyInfo? = null,
)

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
) : ViewModel() {

    /**
     * Dispatcher for the per-result distance mapping/sorting. Defaults to
     * [Dispatchers.Default]; tests override it with a test dispatcher so the
     * work joins deterministically under `advanceUntilIdle()`.
     */
    internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default

    /** Current reference point. Defaults to the Adelaide CBD. */
    var reference: GeoPoint = ADELAIDE_CBD
        private set

    private val _uiState = MutableStateFlow(NearbyUiState())
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    /**
     * Monotonic token identifying the most recent category selection (or
     * reference move). Only the launch whose token still matches the latest may
     * publish results/loading, so a slower earlier query can never overwrite a
     * newer selection's results. Touched only on the main dispatcher, so the
     * plain Int needs no synchronisation.
     */
    private var selectionToken = 0

    init {
        loadEmergency()
    }

    /**
     * Move the reference point. Re-loads the emergency summary for the new
     * location and clears any category results (the previous list is no longer
     * relative to the new reference).
     */
    fun setReference(latitude: Double, longitude: Double) {
        // Invalidate any in-flight category query so a late result can't
        // repopulate the list against the old reference after we've cleared it.
        selectionToken++
        reference = GeoPoint(longitude = longitude, latitude = latitude)
        _uiState.update {
            it.copy(selected = null, results = emptyList(), emergency = null)
        }
        loadEmergency()
    }

    /**
     * Load the nearest POIs of [category], compute each one's distance from the
     * reference, and publish them sorted nearest-first. A corrupt/missing index
     * surfaces as an empty list rather than a crash.
     */
    @Suppress("TooGenericExceptionCaught") // search.db can throw various SQLite errors; fail-safe to empty
    fun selectCategory(category: PoiCategory) {
        val token = ++selectionToken
        _uiState.update { it.copy(selected = category, isLoading = true) }
        viewModelScope.launch {
            try {
                val mapped = withContext(workDispatcher) {
                    searchRepository
                        .nearestByCategory(
                            latitude = reference.latitude,
                            longitude = reference.longitude,
                            category = category,
                            limit = RESULT_LIMIT,
                        )
                        .map { r ->
                            NearbyResult(
                                result = r,
                                distanceMeters = MeasureGeometry.haversineMeters(
                                    reference,
                                    GeoPoint(longitude = r.longitude, latitude = r.latitude),
                                ),
                            )
                        }
                        .sortedBy { it.distanceMeters }
                }
                // Ignore a result that a newer selection (or a reference move)
                // has already superseded.
                if (token == selectionToken) {
                    _uiState.update { it.copy(results = mapped) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A corrupt/missing pack must not crash the screen.
                Log.w(TAG, "Nearby category query failed", e)
                if (token == selectionToken) {
                    _uiState.update { it.copy(results = emptyList()) }
                }
            } finally {
                if (token == selectionToken) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Load the single nearest hospital and the single nearest police station
     * relative to the current reference and publish them as [EmergencyInfo].
     * Best-effort: failures simply leave the affected slot null.
     */
    @Suppress("TooGenericExceptionCaught") // best-effort emergency lookup; never crash the screen
    fun loadEmergency() {
        viewModelScope.launch {
            val info = try {
                val hospital = nearestSingle(PoiCategory.HOSPITAL)
                val police = nearestSingle(PoiCategory.POLICE)
                EmergencyInfo(hospital = hospital, police = police)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Nearby emergency lookup failed", e)
                EmergencyInfo(hospital = null, police = null)
            }
            _uiState.update { it.copy(emergency = info) }
        }
    }

    /** Nearest single POI of [category] with its distance, or null. */
    private suspend fun nearestSingle(category: PoiCategory): NearbyResult? =
        withContext(workDispatcher) {
            searchRepository
                .nearestByCategory(
                    latitude = reference.latitude,
                    longitude = reference.longitude,
                    category = category,
                    limit = 1,
                )
                .firstOrNull()
                ?.let { r ->
                    NearbyResult(
                        result = r,
                        distanceMeters = MeasureGeometry.haversineMeters(
                            reference,
                            GeoPoint(longitude = r.longitude, latitude = r.latitude),
                        ),
                    )
                }
        }

    companion object {
        /** Default reference point: Adelaide CBD. */
        val ADELAIDE_CBD: GeoPoint = GeoPoint(longitude = 138.6007, latitude = -34.9285)

        /** Max POIs fetched per category selection. */
        const val RESULT_LIMIT: Int = 25

        private const val TAG = "NearbyViewModel"
    }
}
