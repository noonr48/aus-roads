package au.com.ausroads

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import au.com.ausroads.di.RoutingInitializer
import au.com.ausroads.di.SearchInitializer
import au.com.ausroads.offline.download.MapPackManager
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AusRoadsApp : Application(), Configuration.Provider {
    @Inject lateinit var searchInitializer: SearchInitializer

    @Inject lateinit var routingInitializer: RoutingInitializer

    // Lazy: injecting MapPackManager eagerly would create it DURING Application
    // injection, and its constructor calls WorkManager.getInstance() -> our
    // workManagerConfiguration -> workerFactory (not yet injected) = crash. Lazy
    // defers creation until the install observer below first reads it (post-injection).
    @Inject lateinit var mapPackManager: Lazy<MapPackManager>

    // Required so WorkManager can instantiate @HiltWorker workers (MapWidgetWorker).
    // Without this, the widget's periodic worker fails to construct at runtime.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        initializePacks("startup")

        // Re-open search + routing whenever a newly installed pack appears, so a
        // pack downloaded in-session is picked up without a full app restart
        // (search.db / valhalla_tiles.tar are otherwise only opened at startup).
        appScope.launch {
            mapPackManager.get().installed
                .map { it?.version }
                .distinctUntilChanged()
                .drop(1) // startup already initialized whatever was installed then
                .collect { version ->
                    Log.i(TAG, "Installed pack changed to v$version — re-initializing search + routing")
                    initializePacks("pack-install:v$version")
                }
        }
    }

    private fun initializePacks(reason: String) {
        Log.i(TAG, "Initializing search index ($reason)")
        runCatching { searchInitializer.initialize() }
            .onFailure { Log.w(TAG, "Search init failed ($reason)", it) }

        // Routing tiles can be large to memory-map; initialize off the main thread
        // so app startup is never blocked by a present routing pack.
        Thread({
            Log.i(TAG, "Initializing routing ($reason)")
            runCatching { routingInitializer.initialize() }
                .onFailure { Log.w(TAG, "Routing init failed ($reason)", it) }
        }, "routing-init").start()
    }

    private companion object {
        private const val TAG = "AusRoadsApp"
    }
}
