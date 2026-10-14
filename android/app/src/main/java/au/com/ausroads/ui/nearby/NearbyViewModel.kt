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
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import au.com.ausroads.di.SearchInitializer
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.PlaceDiscoveryRepository
import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/**
 * Immutable UI state for the Nearby screen.
 *
 * [reference] lives in state (not just on the ViewModel property) because the reference can move at
 * runtime — when a live device fix arrives — and the UI must recompose around it.
 * [usingDeviceLocation] distinguishes a device-fix reference from the Adelaide CBD fallback so the
 * screen can show the "distances are from Adelaide" hint honestly. [emergencyLookupFailed]
 * distinguishes "no emergency POIs found" from "the lookup itself failed".
 */
data class NearbyUiState(
    val categories: List<PoiCategory> = PoiCategory.entries.toList(),
    val selected: PoiCategory? = null,
    val reference: GeoPoint = NearbyViewModel.ADELAIDE_CBD,
    val usingDeviceLocation: Boolean = false,
    val results: List<NearbyResult> = emptyList(),
    val isLoading: Boolean = false,
    val categoryLookupFailed: Boolean = false,
    val emergency: EmergencyInfo? = null,
    val emergencyLookupFailed: Boolean = false,
)

@Suppress("TooManyFunctions") // UI recovery commands and lifecycle-only lookups must remain distinct.
@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val health: PlacesHealth,
    private val initializer: SearchInitializer? = null,
) : ViewModel() {

    /**
     * Dispatcher for the per-result distance mapping/sorting. Defaults to [Dispatchers.Default];
     * tests override it with a test dispatcher so the work joins deterministically under
     * `advanceUntilIdle()`.
     */
    internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default
    internal var catalogDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Records dispatch acceptance only, never a claim that a receiver displayed/shared the place. */
    fun recordShareOutcome(dispatched: Boolean) {
        if (dispatched) health.success(PlacesSeam.LOCATION_SHARE)
        else health.failure(PlacesSeam.LOCATION_SHARE)
    }

    /** Current reference point. Defaults to the Adelaide CBD until a fix lands. */
    val reference: GeoPoint
        get() = _uiState.value.reference

    private val _uiState = MutableStateFlow(NearbyUiState())
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    /**
     * Monotonic token identifying the most recent category selection (or reference move). Only the
     * launch whose token still matches the latest may publish results/loading, so a slower earlier
     * query can never overwrite a newer selection's results. Touched only on the main dispatcher,
     * so the plain Int needs no synchronisation.
     */
    private var selectionToken = 0
    private var emergencyToken = 0
    private var categoryJob: Job? = null
    private var emergencyJob: Job? = null
    private var catalogRetryJob: Job? = null
    private val catalog = searchRepository as? PlaceDiscoveryRepository
    private val availability: DiscoveryAvailability
        get() = catalog?.availability?.value ?: DiscoveryAvailability.READY
    private val generation: Long
        get() = catalog?.generation?.value ?: 0L

    init {
        val lifecycle = catalog
        if (lifecycle == null) lookupEmergency()
        else {
            viewModelScope.launch {
                combine(lifecycle.availability, lifecycle.generation) { state, epoch ->
                        state to epoch
                    }
                    .distinctUntilChanged()
                    .collect { (state, _) -> onCatalogChanged(state) }
            }
        }
    }

    /** Withdraw obsolete emergency/category data even while a replacement is still loading. */
    private fun onCatalogChanged(state: DiscoveryAvailability) {
        selectionToken++
        emergencyToken++
        categoryJob?.cancel()
        emergencyJob?.cancel()
        val failed = state == DiscoveryAvailability.FAILED || state == DiscoveryAvailability.UNAVAILABLE
        _uiState.update {
            it.copy(
                results = emptyList(),
                isLoading = state == DiscoveryAvailability.LOADING && it.selected != null,
                categoryLookupFailed = failed && it.selected != null,
                emergency = null,
                emergencyLookupFailed = failed,
            )
        }
        if (state == DiscoveryAvailability.READY) {
            lookupEmergency()
            _uiState.value.selected?.let(::lookupCategory)
        }
    }

    /**
     * Move the reference point manually. Re-loads the emergency summary for the new location and
     * clears any category results (the previous list is no longer relative to the new reference).
     */
    fun setReference(latitude: Double, longitude: Double) {
        applyReference(
            point = GeoPoint(longitude = longitude, latitude = latitude),
            fromDevice = false,
        )
    }

    /**
     * Adopt a live device fix as the reference point. The first fix always applies; subsequent
     * streaming fixes are ignored until the device has moved more than [MIN_MOVE_METERS], so a
     * continuous location stream cannot keep clearing freshly loaded results every couple of
     * seconds.
     */
    fun onDeviceLocation(latitude: Double, longitude: Double) {
        val candidate = GeoPoint(longitude = longitude, latitude = latitude)
        val movedMeters = MeasureGeometry.haversineMeters(reference, candidate)
        if (_uiState.value.usingDeviceLocation && movedMeters < MIN_MOVE_METERS) return
        applyReference(point = candidate, fromDevice = true)
    }

    /**
     * Shared reference-move path: invalidate any in-flight category query so a late result can't
     * repopulate the list against the old reference after we've cleared it, then reload the
     * emergency summary for the new point.
     */
    private fun applyReference(point: GeoPoint, fromDevice: Boolean) {
        if (point == reference && fromDevice == _uiState.value.usingDeviceLocation) return
        val category = _uiState.value.selected
        selectionToken++
        categoryJob?.cancel()
        _uiState.update {
            it.copy(
                reference = point,
                usingDeviceLocation = fromDevice,
                results = emptyList(),
                isLoading = false,
                categoryLookupFailed = false,
                emergency = null,
                emergencyLookupFailed = false,
            )
        }
        lookupEmergency()
        if (category != null) lookupCategory(category)
    }

    /**
     * Load the nearest POIs of [category], compute each one's distance from the reference, and
     * publish them sorted nearest-first. A corrupt/missing index surfaces as an empty list rather
     * than a crash.
     */
    fun selectCategory(category: PoiCategory) {
        lookupCategory(category)
        retryCatalogIfNeeded()
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // search.db can throw various SQLite errors; fail-safe to empty
    private fun lookupCategory(category: PoiCategory) {
        val token = ++selectionToken
        val origin = reference
        val epoch = generation
        val state = availability
        categoryJob?.cancel()
        _uiState.update {
            it.copy(
                selected = category,
                isLoading = state == DiscoveryAvailability.READY || state == DiscoveryAvailability.LOADING,
                results = emptyList(),
                categoryLookupFailed =
                    state == DiscoveryAvailability.FAILED || state == DiscoveryAvailability.UNAVAILABLE,
            )
        }
        if (state != DiscoveryAvailability.READY) return
        categoryJob = viewModelScope.launch {
            try {
                val mapped =
                    withContext(workDispatcher) {
                        searchRepository
                            .nearestByCategory(
                                latitude = origin.latitude,
                                longitude = origin.longitude,
                                category = category,
                                limit = RESULT_LIMIT,
                            )
                            .map { toNearbyResult(it, origin) }
                            .sortedBy { it.distanceMeters }
                    }
                coroutineContext.ensureActive()
                if (isCurrent(token, selectionToken, origin, epoch)) {
                    _uiState.update { it.copy(results = mapped) }
                    health.success(PlacesSeam.CATALOG_QUERY)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isCurrent(token, selectionToken, origin, epoch)) {
                    Log.w(TAG, "component=places seam=essentials-category class=not-joined")
                    health.failure(PlacesSeam.CATALOG_QUERY)
                    _uiState.update { it.copy(results = emptyList(), categoryLookupFailed = true) }
                }
            } finally {
                if (isCurrent(token, selectionToken, origin, epoch)) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /**
     * Load the single nearest hospital and the single nearest police station relative to the
     * current reference and publish them as [EmergencyInfo]. Best-effort: failures leave the
     * affected slot null AND raise [NearbyUiState.emergencyLookupFailed] so the UI can show an
     * honest error instead of silently looking like "nothing found".
     */
    fun loadEmergency() {
        lookupEmergency()
        retryCatalogIfNeeded()
    }

    @Suppress("TooGenericExceptionCaught") // best-effort emergency lookup; never crash the screen
    private fun lookupEmergency() {
        // Independent token: choosing a category must not cancel emergency publication.
        val token = ++emergencyToken
        val origin = reference
        val epoch = generation
        val state = availability
        emergencyJob?.cancel()
        _uiState.update {
            it.copy(
                emergency = null,
                emergencyLookupFailed =
                    state == DiscoveryAvailability.FAILED || state == DiscoveryAvailability.UNAVAILABLE,
            )
        }
        if (state != DiscoveryAvailability.READY) return
        emergencyJob = viewModelScope.launch {
            var lookupFailed = false
            val info =
                try {
                    val hospital = nearestSingle(PoiCategory.HOSPITAL, origin)
                    val police = nearestSingle(PoiCategory.POLICE, origin)
                    EmergencyInfo(hospital = hospital, police = police)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    lookupFailed = true
                    EmergencyInfo(hospital = null, police = null)
                }
            coroutineContext.ensureActive()
            if (isCurrent(token, emergencyToken, origin, epoch)) {
                if (lookupFailed) {
                    Log.w(TAG, "component=places seam=essentials-emergency class=not-joined")
                    health.failure(PlacesSeam.CATALOG_QUERY)
                } else health.success(PlacesSeam.CATALOG_QUERY)
                _uiState.update { it.copy(emergency = info, emergencyLookupFailed = lookupFailed) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // initializer failures remain fixed, payload-free witnesses
    private fun retryCatalogIfNeeded() {
        if (availability != DiscoveryAvailability.FAILED && availability != DiscoveryAvailability.UNAVAILABLE) return
        val recovery = initializer ?: return // Old unit callers do not own application bootstrap.
        if (catalogRetryJob?.isActive == true) return
        catalogRetryJob = viewModelScope.launch {
            try {
                withContext(catalogDispatcher) {
                    coroutineContext.ensureActive()
                    recovery.initialize() // Synchronous, serialized by SearchInitializer; never on Main.
                }
                // READY/generation observation refreshes the CURRENT category/reference, not a retry snapshot.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                health.failure(PlacesSeam.CATALOG_BOOTSTRAP)
            }
        }
    }

    private fun isCurrent(token: Int, current: Int, origin: GeoPoint, epoch: Long) =
        token == current && reference == origin && epoch == generation &&
            availability == DiscoveryAvailability.READY

    /** Nearest single POI of [category] with its distance, or null. */
    private suspend fun nearestSingle(category: PoiCategory, origin: GeoPoint): NearbyResult? =
        withContext(workDispatcher) {
            searchRepository
                .nearestByCategory(
                    latitude = origin.latitude,
                    longitude = origin.longitude,
                    category = category,
                    limit = 1,
                )
                .firstOrNull()
                ?.let { toNearbyResult(it, origin) }
        }

    private fun toNearbyResult(result: SearchResult, origin: GeoPoint) =
        NearbyResult(
            result = result,
            distanceMeters =
                MeasureGeometry.haversineMeters(
                    origin,
                    GeoPoint(longitude = result.longitude, latitude = result.latitude),
                ),
        )

    companion object {
        /** Default reference point: Adelaide CBD. */
        val ADELAIDE_CBD: GeoPoint = GeoPoint(longitude = 138.6007, latitude = -34.9285)

        /** Max POIs fetched per category selection. */
        const val RESULT_LIMIT: Int = 25

        /** Minimum movement (metres) before a new device fix re-centers Nearby. */
        const val MIN_MOVE_METERS: Double = 25.0

        private const val TAG = "NearbyViewModel"
    }
}
