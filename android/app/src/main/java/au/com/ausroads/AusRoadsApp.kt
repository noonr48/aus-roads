package au.com.ausroads

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import au.com.ausroads.di.RoutingInitializer
import au.com.ausroads.di.SearchInitializer
import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.InstalledPack
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Registration itself performs boot initialization from the current authority.
        // Never drop the initial record: it may already be a replacement that arrived
        // before registration. Subsequent full-record changes (including same-version
        // retries) initialize serially; StateFlow retains the latest while I/O is running.
        appScope.launch {
            collectInstalledPacks({ mapPackManager.get().installed }) {
                Log.i(TAG, "component=packs seam=installed-record class=joined")
                initializePacks()
            }
        }
    }

    private fun initializePacks() {
        runCatching { searchInitializer.initialize() }
            .onFailure { Log.w(TAG, "component=places seam=bootstrap class=not-joined") }

        // Already off Main, and serialized with the next installed-record initialization.
        runCatching { routingInitializer.initialize() }
            .onFailure { Log.w(TAG, "component=packs seam=routing-init class=not-joined") }
    }

    private companion object {
        private const val TAG = "AusRoadsApp"
    }
}

/** Single boot/replacement crossing, also exercised with controlled real SQLite adoptions. */
@Suppress("TooGenericExceptionCaught") // observer registration failure must not suppress catalog boot
internal suspend fun collectInstalledPacks(installed: () -> StateFlow<InstalledPack?>, initialize: () -> Unit) {
    val records = try {
        installed()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Log.w("AusRoadsApp", "component=packs seam=installed-record class=not-joined")
        initialize()
        return
    }
    records.collect { initialize() }
}
