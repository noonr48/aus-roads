package au.com.ausroads.di

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import au.com.ausroads.feature.navigation.DiagnosedNavigationLocationSource
import au.com.ausroads.feature.navigation.NavigationGpsEvent
import au.com.ausroads.feature.navigation.NavigationLocation
import au.com.ausroads.feature.navigation.NavigationLocationSource
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        health: PlacesHealth,
    ): NavigationLocationSource = FusedNavigationLocationSource(
        LocationServices.getFusedLocationProviderClient(context),
        diagnostics = health::navigationGps,
    )
}

private const val MIN_UPDATE_DISTANCE_M = 5f
private const val MIN_UPDATE_INTERVAL_MS = 500L

/** The injectable boundary is the real client; registration Tasks are never replaced by a fake flow. */
internal class FusedNavigationLocationSource(
    private val client: FusedLocationProviderClient,
    private val diagnostics: (NavigationGpsEvent, Int) -> Unit = { _, _ -> },
) : DiagnosedNavigationLocationSource {
    override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> =
        locationUpdates(intervalMs, attempt = 1)

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    override fun locationUpdates(intervalMs: Long, attempt: Int): Flow<NavigationLocation> = callbackFlow {
        currentCoroutineContext().ensureActive()
        val stopped = AtomicBoolean(false)
        val registered = AtomicBoolean(false)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_M)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (stopped.get() || isClosedForSend || !registered.get()) return
                result.lastLocation?.let { loc -> trySend(loc.toNavigationLocation()) }
            }
        }

        recordGpsEvent(NavigationGpsEvent.REGISTRATION_STARTED, attempt)
        try {
            currentCoroutineContext().ensureActive()
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnCompleteListener(TASK_EXECUTOR) { registration ->
                    registrationEnded(registration, attempt, CallbackRegistration(callback, registered),
                        stopped.get() || isClosedForSend) { failure -> close(failure); Unit }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!isClosedForSend) {
                recordGpsEvent(NavigationGpsEvent.REGISTRATION_FAILED, attempt)
                close(IllegalStateException("GPS registration failed"))
            }
        }

        awaitClose {
            stopped.set(true)
            removeCallback(callback, attempt)
        }
    }

    /** Keep the callback identity and its delivery gate together across Task completion. */
    private data class CallbackRegistration(val callback: LocationCallback, val registered: AtomicBoolean)

    private fun registrationEnded(
        registration: Task<Void>,
        attempt: Int,
        callbackRegistration: CallbackRegistration,
        closed: Boolean,
        fail: (Exception) -> Unit,
    ) {
        if (closed) {
            // Late registration after early removal needs the exact callback removed again.
            removeCallback(callbackRegistration.callback, attempt)
            return
        }
        when {
            registration.isCanceled -> {
                recordGpsEvent(NavigationGpsEvent.REGISTRATION_CANCELED, attempt)
                // Provider Task cancellation is retryable, unlike collector/Stop cancellation.
                fail(IllegalStateException("GPS registration canceled"))
            }
            !registration.isSuccessful -> {
                recordGpsEvent(NavigationGpsEvent.REGISTRATION_FAILED, attempt)
                fail(IllegalStateException("GPS registration failed"))
            }
            else -> {
                callbackRegistration.registered.set(true)
                recordGpsEvent(NavigationGpsEvent.REGISTRATION_SUCCEEDED, attempt)
            }
        }
    }

    private fun removeCallback(callback: LocationCallback, attempt: Int) {
        try {
            client.removeLocationUpdates(callback).addOnCompleteListener(TASK_EXECUTOR) { removal ->
                if (!removal.isSuccessful) recordGpsEvent(NavigationGpsEvent.REMOVAL_FAILED, attempt)
            }
        } catch (_: Exception) {
            recordGpsEvent(NavigationGpsEvent.REMOVAL_FAILED, attempt)
        }
    }

    override fun recordGpsEvent(event: NavigationGpsEvent, attempt: Int) {
        val message = "component=navigation seam=gps event=${event.name} attempt=$attempt"
        if (event.failed) Log.w(LOG_TAG, message) else Log.i(LOG_TAG, message)
        diagnostics(event, attempt)
    }

    private companion object {
        const val LOG_TAG = "NavigationGps"
        // Explicit executor: completion must not wait for an Activity/main Handler,
        // particularly after Stop. Also keeps Task tests on the existing test scheduler.
        val TASK_EXECUTOR = Executor { it.run() }
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
