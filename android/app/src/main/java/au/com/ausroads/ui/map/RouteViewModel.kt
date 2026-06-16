package au.com.ausroads.ui.map

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.R
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.CostingProfile
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.RouteResult
import au.com.ausroads.routing.engine.RoutingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RouteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routingEngine: RoutingEngine,
) : ViewModel() {

    private val _routeState = MutableStateFlow<RouteUiState>(RouteUiState.Idle)
    val routeState: StateFlow<RouteUiState> = _routeState.asStateFlow()

    private var origin: GeoPoint? = null
    private var destination: GeoPoint? = null
    private var options: RouteOptions = RouteOptions()

    fun isReady(): Boolean = routingEngine.isReady()

    /**
     * Update the route-avoidance preferences and recompute the current route in
     * place if origin + destination are already set. Seeded from persisted
     * settings and invoked when the user flips an avoid-toggle in the route sheet.
     */
    fun setOptions(options: RouteOptions) {
        this.options = options
        computeIfReady()
    }

    fun setOrigin(point: GeoPoint) {
        origin = point
        computeIfReady()
    }

    fun setDestination(point: GeoPoint) {
        destination = point
        computeIfReady()
    }

    fun clearRoute() {
        origin = null
        destination = null
        _routeState.update { RouteUiState.Idle }
    }

    @Suppress("TooGenericExceptionCaught") // ValhallaException's type isn't on the app classpath
    private fun computeIfReady() {
        val o = origin ?: return
        val d = destination ?: return

        // Surface a clear, user-facing message when no routing pack is installed
        // instead of attempting a route and showing an internal engine error.
        if (!routingEngine.isReady()) {
            _routeState.update { RouteUiState.Error(context.getString(R.string.route_engine_unavailable)) }
            return
        }

        viewModelScope.launch {
            _routeState.update { RouteUiState.Loading }
            try {
                val result = routingEngine.computeRoute(
                    RouteRequest(
                        origin = o,
                        destination = d,
                        costingProfile = CostingProfile.AUTO,
                        options = options,
                    )
                )
                _routeState.update { RouteUiState.Active(result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Route computation failed (network/IO)", e)
                _routeState.update { RouteUiState.Error(context.getString(R.string.route_network_error)) }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Route computation failed (engine state)", e)
                _routeState.update { RouteUiState.Error(context.getString(R.string.route_failed)) }
            } catch (e: Exception) {
                // Valhalla throws ValhallaException (: Exception) when no route exists or
                // the destination is outside the SA tiles. Its concrete type isn't on the
                // app module's classpath, so catch broadly, log for diagnosis, and surface
                // a clear message instead of crashing the coroutine.
                Log.w(TAG, "Route computation failed (no route / engine error)", e)
                _routeState.update { RouteUiState.Error(context.getString(R.string.route_not_found)) }
            }
        }
    }

    private companion object {
        const val TAG = "RouteViewModel"
    }
}

sealed interface RouteUiState {
    data object Idle : RouteUiState
    data object Loading : RouteUiState
    data class Active(val result: RouteResult) : RouteUiState
    data class Error(val message: String) : RouteUiState
}
