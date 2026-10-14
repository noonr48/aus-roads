package au.com.ausroads.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/** Real FAB caller; SDK/platform boundaries are controlled, not a native lifecycle proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationProvidersDiagnosticTest {
    private val context = mockk<Context>()
    private val map = mockk<MapLibreMap>(relaxed = true)
    private val update = mockk<CameraUpdate>()
    private val snackbar = mockk<SnackbarHostState>()
    private val client = mockk<FusedLocationProviderClient>()
    private val task = mockk<Task<Location>>()
    private val messages = mutableListOf<String>()
    private val payloads = mutableListOf<Throwable>()
    private lateinit var success: OnSuccessListener<Location>
    private lateinit var failure: OnFailureListener

    @Before fun setup() {
        mockkStatic(Log::class, ContextCompat::class, LocationServices::class, CameraUpdateFactory::class)
        every { Log.d("UserLocation", any<String>()) } answers { messages += secondArg<String>(); 0 }
        every { Log.i("UserLocation", any<String>()) } answers { messages += secondArg<String>(); 0 }
        every { Log.w("UserLocation", any<String>()) } answers { messages += secondArg<String>(); 0 }
        every { Log.w("UserLocation", any<String>(), any<Throwable>()) } answers {
            messages += secondArg<String>()
            payloads += thirdArg<Throwable>()
            0
        }
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) } returns
            PackageManager.PERMISSION_GRANTED
        every { CameraUpdateFactory.newLatLngZoom(any(), any()) } returns update
        every { LocationServices.getFusedLocationProviderClient(context) } returns client
        every { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null) } returns task
        every { task.addOnSuccessListener(any<OnSuccessListener<Location>>()) } answers {
            success = firstArg(); task
        }
        every { task.addOnFailureListener(any<OnFailureListener>()) } answers {
            failure = firstArg(); task
        }
        coEvery { snackbar.showSnackbar(any(), any(), any(), any()) } returns SnackbarResult.Dismissed
    }

    @After fun cleanup() = unmockkAll()

    @Test fun cachedFixKeepsCameraArgumentsButDoesNotSerializeLocation() = runTest {
        recenterOnUser(map, context, fix(), this, snackbar, "Locating", "Unavailable")
        verify(exactly = 1) { CameraUpdateFactory.newLatLngZoom(LatLng(LATITUDE, LONGITUDE), 15.0) }
        verify(exactly = 1) { map.animateCamera(update, 1000) }
        assertThat(messages).containsExactly("component=user_location seam=recenter_cached class=received")
        assertPayloadFree()
    }

    @Test fun freshFixKeepsCameraArgumentsAndFixedRequestWitness() = runTest {
        recenterOnUser(map, context, null, this, snackbar, "Locating", "Unavailable")
        success.onSuccess(fix())
        verify(exactly = 1) { CameraUpdateFactory.newLatLngZoom(LatLng(LATITUDE, LONGITUDE), 15.0) }
        verify(exactly = 1) { map.animateCamera(update, 1000) }
        assertThat(messages).containsExactly(
            "component=user_location seam=recenter_request class=started",
            "component=user_location seam=recenter_fix class=received",
        ).inOrder()
        assertPayloadFree()
    }

    @Test fun failedTaskKeepsUnavailableFeedbackWithoutThrowablePayload() = runTest {
        recenterOnUser(map, context, null, this, snackbar, "Locating", "Unavailable")
        failure.onFailure(SecurityException(PRIVATE_FAILURE)) // Kill the actual registered caller seam.
        runCurrent()
        coVerify(exactly = 1) { snackbar.showSnackbar("Locating", any(), any(), any()) }
        coVerify(exactly = 1) { snackbar.showSnackbar("Unavailable", any(), any(), any()) }
        verify(exactly = 0) { map.animateCamera(any(), any<Int>()) }
        assertThat(messages).containsExactly(
            "component=user_location seam=recenter_request class=started",
            "component=user_location seam=recenter_task class=failed",
        ).inOrder()
        assertPayloadFree()
    }

    private fun assertPayloadFree() {
        assertThat(payloads).isEmpty()
        assertThat(messages.all { it.matches(Regex("component=user_location seam=[a-z_]+ class=[a-z_]+")) }).isTrue()
        assertThat(messages.joinToString()).doesNotContain(LATITUDE.toString())
        assertThat(messages.joinToString()).doesNotContain(LONGITUDE.toString())
        assertThat(messages.joinToString()).doesNotContain(PRIVATE_FAILURE)
    }

    private fun fix() = mockk<Location> {
        every { latitude } returns LATITUDE
        every { longitude } returns LONGITUDE
        every { accuracy } returns 4.5f
    }

    private companion object {
        const val LATITUDE = -34.91234567
        const val LONGITUDE = 138.61234567
        const val PRIVATE_FAILURE = "synthetic private provider text -34.91234567,138.61234567"
    }
}
