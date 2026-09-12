package au.com.ausroads.navigation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import au.com.ausroads.MainActivity
import au.com.ausroads.R

/**
 * withNetwork flavor: location-type foreground service that keeps GPS
 * updates flowing while a turn-by-turn navigation session runs and the
 * screen is off or the app is backgrounded (the process would otherwise be
 * frozen and its location updates paused).
 *
 * The service is a passive keep-alive: the map screen's NavigationViewModel
 * owns the session. The notification's Stop action publishes on
 * [NavigationSessionBus] — the service is process-global while the
 * ViewModel is screen-scoped — and the map screen, which collects the bus,
 * ends the session and calls [NavigationSessionService.stop].
 *
 * Started only via [NavigationSessionService.start], which refuses to run
 * without ACCESS_FINE_LOCATION, so the API 34+ requirement that a
 * location-type promotion hold while-in-use location permission is met.
 */
class NavigationForegroundService : Service() {

    /** Not a bound service; location promotion happens via onStartCommand. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Creates the notification channel (idempotent) before any start. */
    override fun onCreate() {
        super.onCreate()
        createChannelIfAbsent()
    }

    /** Handles [ACTION_START] (promote) and [ACTION_STOP] (request stop, demote). */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                NavigationSessionBus.requestStop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            // ACTION_START — and any unexpected action, including a null one:
            // after ContextCompat.startForegroundService() the service MUST reach
            // startForeground() quickly or the system throws; the guarded
            // promotion below degrades to stopSelf() when not permitted, so this
            // can never crash.
            else -> startInForeground()
        }
        // The map screen owns the session; restarting a keep-alive with no
        // session behind it would show a lying notification.
        return START_NOT_STICKY
    }

    /**
     * Promotes this service to a location-type foreground service with the
     * ongoing navigation notification. Never crashes: if the platform
     * rejects the promotion (e.g. location permission was revoked between
     * session start and delivery on API 34+), the service gives up quietly.
     */
    private fun startInForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: SecurityException) {
            // While-in-use permission was revoked between the start guard and
            // promotion; fail safe (never crash) and drop the session loudly
            // enough that the failure is diagnosable from logcat.
            android.util.Log.w(LOG_TAG, "Foreground promotion denied; stopping service", e)
            stopSelf()
        } catch (e: IllegalStateException) {
            // API 31+ ForegroundServiceStartNotAllowedException surfaces as
            // IllegalStateException when the OS refuses background promotion —
            // same fail-safe: stop rather than crash the navigation app.
            android.util.Log.w(LOG_TAG, "Foreground promotion not allowed; stopping service", e)
            stopSelf()
        }
    }

    /**
     * Creates the low-importance 'navigation' channel on first run so the
     * ongoing notification is silent (turn-by-turn voice guidance already
     * occupies the audio focus).
     */
    private fun createChannelIfAbsent() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nav_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    /** Ongoing status-bar notification: title, tap-to-open, Stop action. */
    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(SMALL_ICON)
        .setContentTitle(getString(R.string.nav_service_title))
        .setContentIntent(contentPendingIntent())
        .addAction(NO_ACTION_ICON, getString(R.string.nav_service_stop), stopPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    /**
     * Opens [MainActivity] (single_top so an in-place task resumes instead
     * of stacking a second activity) when the notification body is tapped.
     */
    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_CODE_OPEN_APP,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Stop button on the notification: routes ACTION_STOP back here. */
    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_CODE_STOP,
        Intent(this, NavigationForegroundService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /** Intent action that promotes the service into the foreground. */
        const val ACTION_START = "au.com.ausroads.navigation.action.START_NAVIGATION"

        /** Intent action from the notification Stop button. */
        const val ACTION_STOP = "au.com.ausroads.navigation.action.STOP_NAVIGATION"

        /** Stable id of the ongoing navigation notification. */
        private const val NOTIFICATION_ID = 4001

        /** Logcat tag for the (rare) foreground-promotion failure path. */
        private const val LOG_TAG = "NavigationForegroundService"

        /** Id of the silent, low-importance 'navigation' channel. */
        private const val CHANNEL_ID = "navigation"

        /**
         * Status-bar small icon. The app ships no plain drawables (only
         * adaptive launcher mipmaps, which are not status-bar icons), so
         * the platform location pin is used.
         */
        private const val SMALL_ICON = android.R.drawable.ic_menu_mylocation

        /** PendingIntent request codes (distinct to avoid intent clobbering). */
        private const val REQUEST_CODE_OPEN_APP = 4101
        private const val REQUEST_CODE_STOP = 4102

        /** Deprecated action icons are ignored on API 28+; none is supplied. */
        private const val NO_ACTION_ICON = 0
    }
}
