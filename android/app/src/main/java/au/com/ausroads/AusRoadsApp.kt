package au.com.ausroads

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import au.com.ausroads.core.common.PackRuntimeGenerationGate
import au.com.ausroads.di.PackRuntimeCoordinator
import au.com.ausroads.di.RoutingInitializer
import au.com.ausroads.di.SearchInitializer
import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.installDirectoryName
import au.com.ausroads.ui.map.MapPackAvailability
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltAndroidApp
class AusRoadsApp : Application(), Configuration.Provider {
    @Inject lateinit var searchInitializer: SearchInitializer

    @Inject lateinit var routingInitializer: RoutingInitializer

    @Inject lateinit var packRuntimeCoordinator: PackRuntimeCoordinator

    // Lazy: injecting MapPackManager eagerly would create it DURING Application
    // injection, and its constructor calls WorkManager.getInstance() -> our
    // workManagerConfiguration -> workerFactory (not yet injected) = crash. Lazy
    // defers creation until the install observer below first reads it (post-injection).
    @Inject lateinit var mapPackManager: Lazy<MapPackManager>

    // Required so WorkManager can instantiate @HiltWorker workers (MapWidgetWorker).
    // Without this, the widget's periodic worker fails to construct at runtime.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationMutex = Mutex()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Reconcile current.json before opening any component. Otherwise a stale
        // descriptor can make startup close search, then disappear as null -> null
        // without producing the installed-flow event that used to repair it.
        appScope.launch {
            val manager = mapPackManager.get()
            var isStartup = true
            reconciledPacks(manager.installedStateInitialized, manager.installed)
                .collect { pack ->
                    val identity = pack?.let {
                        "${it.version}:${it.installedAt}:${it.installDirectoryName()}"
                    }
                    val reason = if (isStartup) "startup:$identity" else "pack-change:$identity"
                    isStartup = false
                    val result = initializePacks(pack, reason)
                    result.onFailure { error ->
                        Log.w(TAG, "Pack generation activation failed; keeping prior generation ($reason)", error)
                    }
                    if (result.isFailure && pack != null) {
                        runCatching { manager.rollbackFailedActivation(pack) }
                            .onSuccess { restored ->
                                Log.w(
                                    TAG,
                                    "Rejected pack was rolled back; next generation=" +
                                        (restored?.version ?: "bundled"),
                                )
                            }
                            .onFailure { rollbackError ->
                                Log.e(TAG, "Could not roll back rejected pack metadata", rollbackError)
                            }
                    }
                }
        }
    }

    private suspend fun initializePacks(pack: InstalledPack?, reason: String): Result<Unit> =
        initializationMutex.withLock {
            // Validate the map, then prepare routing and search without mutating
            // the active generation. Every failure remains inside the activation
            // Result so the caller can reject and roll back a downloaded pack.
            Log.i(TAG, "Preparing routing ($reason)")
            activatePackGeneration(
                pack = pack,
                prepareMap = {
                    if (pack != null) {
                        checkNotNull(MapPackAvailability.installedPackMbtiles(this, pack)) {
                            "Installed map pack has no usable tile database"
                        }
                    }
                },
                prepareRouting = { routingInitializer.prepare(pack) },
                prepareSearch = {
                    Log.i(TAG, "Preparing search index ($reason)")
                    searchInitializer.prepare(pack)
                },
                activateRouting = routingInitializer::activate,
                activateSearch = searchInitializer::activate,
                publish = packRuntimeCoordinator::publish,
            ).onSuccess {
                Log.i(TAG, "Activated map/search/routing generation ($reason)")
            }
        }

    private companion object {
        private const val TAG = "AusRoadsApp"
    }
}

/**
 * Two-phase pack activation shared by startup and live replacement.
 *
 * The map is validated and both candidates are fully prepared before the write
 * lease begins. Their commit methods contain no I/O and cannot fail under normal
 * use. Search, routing, and the map descriptor are then swapped while readers
 * are excluded. A preparation failure closes any candidate and leaves the prior
 * generation and coordinator publication untouched.
 */
@Suppress("LongParameterList")
internal fun <Routing : AutoCloseable, Search : AutoCloseable> activatePackGeneration(
    pack: InstalledPack?,
    prepareMap: () -> Unit = {},
    prepareRouting: () -> Routing,
    prepareSearch: () -> Search,
    activateRouting: (Routing) -> Unit,
    activateSearch: (Search) -> Unit,
    publish: (InstalledPack?) -> Unit,
): Result<Unit> = runCatching {
    prepareMap()
    prepareRouting().use { routing ->
        prepareSearch().use { search ->
            PackRuntimeGenerationGate.write {
                activateRouting(routing)
                activateSearch(search)
                publish(pack)
            }
        }
    }
}

/**
 * Wait for launch reconciliation, then emit the current canonical pack and
 * every later replacement. Collecting the StateFlow only after the readiness
 * gate closes both boundary races: null -> null repair still emits once, and a
 * pack installed between the gate and collection is replayed as the latest value.
 */
internal fun reconciledPacks(
    installedStateInitialized: Flow<Boolean>,
    installed: StateFlow<InstalledPack?>,
): Flow<InstalledPack?> = flow {
    installedStateInitialized.first { it }
    emitAll(installed)
}
