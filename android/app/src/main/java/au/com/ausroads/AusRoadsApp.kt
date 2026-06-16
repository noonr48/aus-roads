package au.com.ausroads

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import au.com.ausroads.di.RoutingInitializer
import au.com.ausroads.di.SearchInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AusRoadsApp : Application(), Configuration.Provider {
    @Inject lateinit var searchInitializer: SearchInitializer

    @Inject lateinit var routingInitializer: RoutingInitializer

    // Required so WorkManager can instantiate @HiltWorker workers (MapWidgetWorker).
    // Without this, the widget's periodic worker fails to construct at runtime.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        searchInitializer.initialize()
        // Routing tiles can be large to memory-map; initialize off the main
        // thread so app startup is never blocked by a present routing pack.
        Thread({
            runCatching { routingInitializer.initialize() }
                .onFailure { Log.w("AusRoadsApp", "Routing init failed", it) }
        }, "routing-init").start()
    }
}
