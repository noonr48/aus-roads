/*
 * User-tunable settings. Persisted in DataStore<Preferences>.
 * v0.1.1: theme, attribution overlay toggle, live traffic toggle. The traffic toggle
 * is read by the live traffic provider registry; in v0.1 the stub provider ignores it.
 */
package au.com.ausroads.data.settings

enum class ThemeMode { System, Light, Dark }

data class Settings(
    val theme: ThemeMode = ThemeMode.System,
    val showAttributionOverlay: Boolean = true,
    val liveTrafficEnabled: Boolean = false,
    val enabledTrafficSources: Set<String> = setOf("traffic-sa", "dit-outback"),
    val ttsEnabled: Boolean = true,
    val congestionOverlayEnabled: Boolean = false,
    val nswTrafficApiKey: String = "",
    val vicTrafficApiKey: String = "",
    val avoidTolls: Boolean = false,
    val avoidUnsealed: Boolean = false,
    val avoidFerries: Boolean = false,
)
