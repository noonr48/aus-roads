package au.com.ausroads.ui.map

import androidx.lifecycle.ViewModel
import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.InstalledPack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the reactive map-pack install state to the map UI.
 *
 * MapScreen previously probed the filesystem once per composition
 * (via remember(context)), so a pack downloaded in Settings stayed invisible
 * ("No map pack installed") until the activity was recreated. Following the
 * same Hilt wiring pattern as [au.com.ausroads.ui.settings.MapPackViewModel],
 * this view model forwards MapPackManager's installed StateFlow unchanged so
 * the placeholder / demo-banner decisions react to installs immediately.
 */
@HiltViewModel
class MapPackStateViewModel @Inject constructor(
    mapPackManager: MapPackManager,
) : ViewModel() {

    /** The currently installed downloaded pack, or null while none is installed. */
    val installed: StateFlow<InstalledPack?> = mapPackManager.installed
}
