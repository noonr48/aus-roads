package au.com.ausroads.di

import android.content.Context
import au.com.ausroads.routing.engine.valhalla.ValhallaRoutingEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Initializes the Valhalla routing engine from installed routing tiles, if
 * present. Mirrors [SearchInitializer]: directions stay gracefully unavailable
 * (RouteViewModel surfaces a "routing not available" message) until a pack with
 * routing tiles is installed.
 *
 * Injects the concrete [ValhallaRoutingEngine] because `initialize(tarPath)` is
 * engine-specific. It is the same @Singleton instance RouteViewModel resolves
 * via the RoutingEngine interface, so initializing here makes routing ready
 * app-wide.
 */
@Singleton
class RoutingInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routingEngine: ValhallaRoutingEngine,
    private val health: PlacesHealth,
) {
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // failed configuration stays explicitly unavailable
    fun initialize() {
        try {
            when (val selected = InstalledPackPaths.routing(context)) {
                is InstalledPackPaths.RoutingSelection.Available -> {
                    routingEngine.initialize(selected.file.absolutePath)
                    // Configuration construction is not a usable-graph or successful-route proof.
                    health.routingInit(RoutingInitOutcome.CONFIGURED)
                }
                InstalledPackPaths.RoutingSelection.Unavailable -> {
                    routingEngine.invalidate()
                    health.routingInit(RoutingInitOutcome.UNAVAILABLE)
                }
                InstalledPackPaths.RoutingSelection.Rejected -> {
                    routingEngine.invalidate()
                    health.routingInit(RoutingInitOutcome.REJECTED)
                }
            }
        } catch (cancelled: CancellationException) {
            routingEngine.invalidate()
            throw cancelled
        } catch (_: Exception) {
            configurationFailed()
        } catch (_: LinkageError) {
            configurationFailed()
        }
    }

    private fun configurationFailed() {
        routingEngine.invalidate()
        health.routingInit(RoutingInitOutcome.CONFIGURATION_FAILED)
    }
}
