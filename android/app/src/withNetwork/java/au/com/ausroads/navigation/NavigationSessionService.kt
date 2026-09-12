package au.com.ausroads.navigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * withNetwork flavor: promotes an active turn-by-turn navigation session
 * into a location-type foreground service so GPS updates survive screen-off
 * and app switches mid-drive.
 *
 * Call sites stay flavor-agnostic via the no-op offline twin at
 * app/src/offline/java/au/com/ausroads/navigation/NavigationSessionService.kt
 * (the privacy-first offline flavor strips all location permissions, so it
 * never promotes a session).
 */
object NavigationSessionService {

    /**
     * Starts [NavigationForegroundService] so the current navigation session
     * keeps receiving location updates while the screen is off or the app is
     * backgrounded.
     *
     * Guarded: a no-op unless [Manifest.permission.ACCESS_FINE_LOCATION] is
     * currently granted — a location-type foreground service must never be
     * started (and must never call startForeground) without it, or the
     * platform throws SecurityException on API 34+.
     */
    fun start(context: Context) {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineLocationGranted) return

        val startIntent = Intent(context, NavigationForegroundService::class.java)
            .setAction(NavigationForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, startIntent)
    }

    /**
     * Stops [NavigationForegroundService], ending the foreground promotion.
     * The map screen owns the session state, so this does not emit on
     * [NavigationSessionBus] — the caller already knows the session ended.
     */
    fun stop(context: Context) {
        context.stopService(Intent(context, NavigationForegroundService::class.java))
    }
}
