/*
 * SettingsRepository — interface + DataStore-backed implementation.
 * Reads and writes the three v0.1.1 settings. Reactive: a Flow<Settings> is exposed
 * for the UI; `update*` methods write and the Flow re-emits.
 */
package au.com.ausroads.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setTheme(mode: ThemeMode)
    suspend fun setShowAttributionOverlay(show: Boolean)
    suspend fun setLiveTrafficEnabled(enabled: Boolean)
    suspend fun setTrafficSourceEnabled(sourceId: String, enabled: Boolean)
    suspend fun setTtsEnabled(enabled: Boolean)
    suspend fun setCongestionOverlayEnabled(enabled: Boolean)
    suspend fun setNswTrafficApiKey(key: String)
    suspend fun setVicTrafficApiKey(key: String)
    suspend fun setAvoidOptions(avoidTolls: Boolean, avoidUnsealed: Boolean, avoidFerries: Boolean)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val SHOW_ATTRIBUTION = booleanPreferencesKey("show_attribution")
        val LIVE_TRAFFIC = booleanPreferencesKey("live_traffic")
        val ENABLED_TRAFFIC_SOURCES = stringSetPreferencesKey("enabled_traffic_sources")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val CONGESTION_OVERLAY = booleanPreferencesKey("congestion_overlay")
        val NSW_TRAFFIC_API_KEY = stringPreferencesKey("nsw_traffic_api_key")
        val VIC_TRAFFIC_API_KEY = stringPreferencesKey("vic_traffic_api_key")
        val AVOID_TOLLS = booleanPreferencesKey("avoid_tolls")
        val AVOID_UNSEALED = booleanPreferencesKey("avoid_unsealed")
        val AVOID_FERRIES = booleanPreferencesKey("avoid_ferries")
    }

    override val settings: Flow<Settings> = store.data.map { prefs ->
        Settings(
            theme = prefs[Keys.THEME]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.System,
            showAttributionOverlay = prefs[Keys.SHOW_ATTRIBUTION] ?: true,
            liveTrafficEnabled = prefs[Keys.LIVE_TRAFFIC] ?: false,
            enabledTrafficSources = prefs[Keys.ENABLED_TRAFFIC_SOURCES]
                ?: setOf("traffic-sa", "dit-outback"),
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: true,
            congestionOverlayEnabled = prefs[Keys.CONGESTION_OVERLAY] ?: false,
            nswTrafficApiKey = prefs[Keys.NSW_TRAFFIC_API_KEY] ?: "",
            vicTrafficApiKey = prefs[Keys.VIC_TRAFFIC_API_KEY] ?: "",
            avoidTolls = prefs[Keys.AVOID_TOLLS] ?: false,
            avoidUnsealed = prefs[Keys.AVOID_UNSEALED] ?: false,
            avoidFerries = prefs[Keys.AVOID_FERRIES] ?: false,
        )
    }

    override suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[Keys.THEME] = mode.name }
    }

    override suspend fun setShowAttributionOverlay(show: Boolean) {
        store.edit { it[Keys.SHOW_ATTRIBUTION] = show }
    }

    override suspend fun setLiveTrafficEnabled(enabled: Boolean) {
        store.edit { it[Keys.LIVE_TRAFFIC] = enabled }
    }

    override suspend fun setTrafficSourceEnabled(sourceId: String, enabled: Boolean) {
        store.edit { prefs ->
            val current = prefs[Keys.ENABLED_TRAFFIC_SOURCES]
                ?: setOf("traffic-sa", "dit-outback")
            prefs[Keys.ENABLED_TRAFFIC_SOURCES] = if (enabled) {
                current + sourceId
            } else {
                current - sourceId
            }
        }
    }

    override suspend fun setTtsEnabled(enabled: Boolean) {
        store.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    override suspend fun setCongestionOverlayEnabled(enabled: Boolean) {
        store.edit { it[Keys.CONGESTION_OVERLAY] = enabled }
    }

    override suspend fun setNswTrafficApiKey(key: String) {
        store.edit { it[Keys.NSW_TRAFFIC_API_KEY] = key }
    }

    override suspend fun setVicTrafficApiKey(key: String) {
        store.edit { it[Keys.VIC_TRAFFIC_API_KEY] = key }
    }

    override suspend fun setAvoidOptions(
        avoidTolls: Boolean,
        avoidUnsealed: Boolean,
        avoidFerries: Boolean,
    ) {
        // Single atomic write so the settings Flow emits once per change, not
        // three times (which would trigger up to three route recomputes).
        store.edit {
            it[Keys.AVOID_TOLLS] = avoidTolls
            it[Keys.AVOID_UNSEALED] = avoidUnsealed
            it[Keys.AVOID_FERRIES] = avoidFerries
        }
    }
}
