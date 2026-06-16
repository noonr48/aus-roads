package au.com.ausroads.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

data class MapWidgetState(
    val trafficEventCount: Int = 0,
    val packInstalled: Boolean = false,
    val packVersion: String? = null,
    val lastUpdatedEpochMs: Long = 0L,
    val hasError: Boolean = false,
) {
    companion object {
        val KEY_TRAFFIC_EVENT_COUNT = intPreferencesKey("traffic_event_count")
        val KEY_PACK_INSTALLED = booleanPreferencesKey("pack_installed")
        val KEY_PACK_VERSION = stringPreferencesKey("pack_version")
        val KEY_LAST_UPDATED_EPOCH_MS = longPreferencesKey("last_updated_epoch_ms")
        val KEY_HAS_ERROR = booleanPreferencesKey("has_error")
    }
}

fun Preferences.toMapWidgetState(): MapWidgetState = MapWidgetState(
    trafficEventCount = this[MapWidgetState.KEY_TRAFFIC_EVENT_COUNT] ?: 0,
    packInstalled = this[MapWidgetState.KEY_PACK_INSTALLED] ?: false,
    packVersion = this[MapWidgetState.KEY_PACK_VERSION],
    lastUpdatedEpochMs = this[MapWidgetState.KEY_LAST_UPDATED_EPOCH_MS] ?: 0L,
    hasError = this[MapWidgetState.KEY_HAS_ERROR] ?: false,
)
