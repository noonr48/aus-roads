package au.com.ausroads.navigation

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Cross-flavor channel for navigation-session control signals.
 *
 * The withNetwork flavor's foreground-service notification exposes a Stop
 * action; the service is process-global while the NavigationViewModel is
 * screen-scoped, so the service publishes stop requests here and the map
 * screen (which owns the ViewModel) collects them and ends the session.
 *
 * The offline flavor never starts a service, so the flow simply stays idle.
 */
object NavigationSessionBus {

    private val _stopRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits when the foreground-service Stop action fires. */
    val stopRequests: SharedFlow<Unit> = _stopRequests

    /** Called by the withNetwork foreground service when the user taps Stop. */
    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }
}
