package au.com.ausroads.di

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import au.com.ausroads.feature.navigation.NavigationLocation
import au.com.ausroads.feature.navigation.NavigationLocationUnavailableException
import au.com.ausroads.feature.navigation.NavigationLocationSource
import au.com.ausroads.ui.map.hasForegroundLocationPermission
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** No-network builds navigate from Android's on-device GPS provider. */
@Module
@InstallIn(SingletonComponent::class)
object NavigationLocationModule {
    @Provides
    @Singleton
    fun provideNavigationLocationSource(
        @ApplicationContext context: Context,
    ): NavigationLocationSource = NativeNavigationLocationSource(context)
}

private const val MIN_UPDATE_DISTANCE_METERS = 0f

private class NativeNavigationLocationSource(
    private val context: Context,
) : NavigationLocationSource {
    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> = callbackFlow {
        if (!context.hasForegroundLocationPermission()) {
            close(SecurityException("Foreground location permission is not granted"))
            return@callbackFlow
        }

        val manager = context.getSystemService(LocationManager::class.java)
        var receivedFix = false
        val watchdog = LocationStreamWatchdog(this) {
            close(NavigationLocationUnavailableException(timeoutMessage(receivedFix)))
        }
        watchdog.arm(INITIAL_FIX_TIMEOUT_MS)
        val activeTimeoutMillis = maxOf(
            ACTIVE_FIX_TIMEOUT_MS,
            intervalMs * ACTIVE_TIMEOUT_INTERVAL_MULTIPLIER,
        )
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                receivedFix = true
                watchdog.arm(activeTimeoutMillis)
                trySend(location.toNavigationLocation())
            }

            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) {
                if (provider in NAVIGATION_PROVIDERS && !manager.hasEnabledNavigationProvider()) {
                    watchdog.cancel()
                    close(NavigationLocationUnavailableException("Android Location is turned off"))
                }
            }
        }
        val providers = manager.enabledNavigationProviders()
        if (providers.isEmpty()) {
            watchdog.cancel()
            close(NavigationLocationUnavailableException("Android Location is turned off"))
            return@callbackFlow
        }
        val started = providers.count { provider ->
            manager.startNavigationUpdates(provider, intervalMs, listener)
        }
        if (started == 0) {
            watchdog.cancel()
            close(NavigationLocationUnavailableException("No location provider could start"))
            return@callbackFlow
        }

        awaitClose {
            watchdog.cancel()
            manager.removeUpdates(listener)
        }
    }

    private fun Location.toNavigationLocation() = NavigationLocation(
        latitude = latitude,
        longitude = longitude,
        speedKmh = (speed * 3.6).coerceAtLeast(0.0),
        bearing = bearing,
        accuracyMeters = accuracy,
        timestamp = time,
    )
}

private fun timeoutMessage(receivedFix: Boolean): String =
    if (receivedFix) "Location updates stopped" else "No location fix was received"

private fun LocationManager.enabledNavigationProviders(): List<String> =
    NAVIGATION_PROVIDERS.filter(::isProviderEnabledSafely)

private fun LocationManager.hasEnabledNavigationProvider(): Boolean =
    NAVIGATION_PROVIDERS.any(::isProviderEnabledSafely)

private fun LocationManager.isProviderEnabledSafely(provider: String): Boolean =
    runCatching { isProviderEnabled(provider) }.getOrDefault(false)

@SuppressLint("MissingPermission")
private fun LocationManager.startNavigationUpdates(
    provider: String,
    intervalMs: Long,
    listener: LocationListener,
): Boolean = runCatching {
    requestLocationUpdates(
        provider,
        intervalMs,
        MIN_UPDATE_DISTANCE_METERS,
        listener,
        Looper.getMainLooper(),
    )
}.isSuccess

private const val INITIAL_FIX_TIMEOUT_MS = 15_000L
private const val ACTIVE_FIX_TIMEOUT_MS = 30_000L
private const val ACTIVE_TIMEOUT_INTERVAL_MULTIPLIER = 3L
private val NAVIGATION_PROVIDERS = listOf(
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
)
