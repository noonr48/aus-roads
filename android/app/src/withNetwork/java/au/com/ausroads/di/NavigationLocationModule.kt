package au.com.ausroads.di

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import au.com.ausroads.feature.navigation.NavigationLocation
import au.com.ausroads.feature.navigation.NavigationLocationSource
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * withNetwork flavor: binds the real fused-location-backed navigation source.
 * This module (and the play-services-location dependency it needs) is confined to
 * the withNetwork source set so the privacy-first offline flavor links no Google
 * Play Services and contains no location code.
 */
@Module
@InstallIn(SingletonComponent::class)
object NavigationLocationModule {
    @Provides
    @Singleton
    fun provideNavigationLocationSource(
        @ApplicationContext context: Context,
    ): NavigationLocationSource = FusedNavigationLocationSource(context)
}

private const val MIN_UPDATE_DISTANCE_M = 5f
private const val MIN_UPDATE_INTERVAL_MS = 500L

private class FusedNavigationLocationSource(
    context: Context,
) : NavigationLocationSource {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_M)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    trySend(loc.toNavigationLocation())
                }
            }
        }

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose {
            client.removeLocationUpdates(callback)
        }
    }

    private fun Location.toNavigationLocation() = NavigationLocation(
        latitude = latitude,
        longitude = longitude,
        speedKmh = (speed * 3.6).coerceAtLeast(0.0), // m/s to km/h
        bearing = bearing,
        accuracyMeters = accuracy,
        timestamp = time,
    )
}
