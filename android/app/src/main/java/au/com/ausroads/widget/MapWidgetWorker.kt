package au.com.ausroads.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.traffic.provider.LiveTrafficProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class MapWidgetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val providers: Set<@JvmSuppressWildcards LiveTrafficProvider>,
    private val packStateStore: PackStateStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val eventCount = fetchTotalEventCount()
        val currentPack = packStateStore.readCurrent()
        val hasError = eventCount == -1

        val glanceIds = GlanceAppWidgetManager(applicationContext)
            .getGlanceIds(MapWidget::class.java)

        val widget = MapWidget()
        for (glanceId in glanceIds) {
            updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[MapWidgetState.KEY_TRAFFIC_EVENT_COUNT] = if (hasError) 0 else eventCount
                    this[MapWidgetState.KEY_PACK_INSTALLED] = currentPack != null
                    this[MapWidgetState.KEY_PACK_VERSION] = currentPack?.version ?: ""
                    this[MapWidgetState.KEY_LAST_UPDATED_EPOCH_MS] = System.currentTimeMillis()
                    this[MapWidgetState.KEY_HAS_ERROR] = hasError
                }
            }
            widget.update(applicationContext, glanceId)
        }
        return Result.success()
    }

    private suspend fun fetchTotalEventCount(): Int {
        var totalCount = 0
        var anySucceeded = false
        for (provider in providers) {
            try {
                val eventsResult = provider.fetchEvents(bbox = null)
                val closuresResult = provider.fetchClosures(bbox = null)
                totalCount += eventsResult.events.size + closuresResult.events.size
                anySucceeded = true
            } catch (_: Exception) {
                // Individual provider failure — continue with others
            }
        }
        return if (anySucceeded) totalCount else -1
    }

    companion object {
        const val WORK_NAME = "MapWidgetWorker"
    }
}
