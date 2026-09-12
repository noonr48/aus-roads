package au.com.ausroads.navigation

import android.content.Context

/**
 * Offline-flavor twin of the withNetwork navigation foreground service.
 *
 * The privacy-first offline flavor strips all location permissions, so
 * turn-by-turn navigation is refused before it starts
 * (NavigationState.LocationUnavailable) and there is no live session to
 * promote. This no-op twin keeps the map screen's call sites
 * flavor-agnostic; see the real implementation in the withNetwork source
 * set.
 */
object NavigationSessionService {

    // Parameters keep the call sites identical across flavors; the offline
    // twin intentionally ignores them (no service exists in this build).
    @Suppress("UnusedParameter")
    fun start(context: Context) = Unit

    @Suppress("UnusedParameter")
    fun stop(context: Context) = Unit
}
