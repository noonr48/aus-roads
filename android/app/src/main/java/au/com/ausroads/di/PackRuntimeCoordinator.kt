package au.com.ausroads.di

import au.com.ausroads.offline.download.state.InstalledPack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Publishes a pack to map consumers only after its search and routing components are prepared. */
@Singleton
class PackRuntimeCoordinator @Inject constructor() {
    private val _activePack = MutableStateFlow<InstalledPack?>(null)
    val activePack: StateFlow<InstalledPack?> = _activePack.asStateFlow()

    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    internal fun publish(pack: InstalledPack?) {
        _activePack.value = pack
        _initialized.value = true
    }
}
