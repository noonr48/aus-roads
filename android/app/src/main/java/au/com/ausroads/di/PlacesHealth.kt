@file:Suppress("TooGenericExceptionCaught")

package au.com.ausroads.di

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import au.com.ausroads.feature.navigation.NavigationGpsEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

enum class PlacesSeam {
    CATALOG_BOOTSTRAP,
    CATALOG_OPEN,
    CATALOG_QUERY,
    MAP_SEARCH_QUERY,
    MAP_ASSETS,
    MAP_STYLE,
    CAMERAS_QUERY,
    PREFERENCES_READ,
    PREFERENCES_WRITE,
    SETTINGS_READ,
    SETTINGS_WRITE,
    MAP_SELECTION,
    LOCATION_SHARE,
    ROAD_SPEED_QUERY,
    NAVIGATION_GPS,
    ROUTING_INIT,
}

enum class RoutingInitOutcome(val failed: Boolean) {
    CONFIGURED(false),
    UNAVAILABLE(false),
    REJECTED(true),
    CONFIGURATION_FAILED(true),
}

/** Fixed-cardinality, device-only witnesses. All read/write/fsync work is off the UI thread. */
@Singleton
class PlacesHealth @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "places-health.json"))
    private val emitted = ConcurrentHashMap<String, AtomicInteger>()
    private val suppressed = AtomicInteger()
    private val writer =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(64),
            { task -> Thread(task, "places-health").apply { isDaemon = true } },
        )
    // Evaluated only by the sole I/O worker, never during Hilt injection/main-thread callbacks.
    private val state by
        lazy(LazyThreadSafetyMode.NONE) {
            try {
                if (file.baseFile.isFile) {
                    check(file.baseFile.length() <= MAX_STATE_BYTES) { "Oversized health state" }
                    JSONObject(file.openRead().bufferedReader().use { it.readText() })
                } else JSONObject()
            } catch (_: Exception) {
                witness("health-read", "contract-drift")
                JSONObject().put("health_read_failures", 1L)
            }
        }

    fun success(seam: PlacesSeam) = enqueue(seam, failure = false)

    fun failure(seam: PlacesSeam) = enqueue(seam, failure = true)

    fun routingInit(outcome: RoutingInitOutcome) =
        enqueue(PlacesSeam.ROUTING_INIT, outcome.failed, routingOutcome = outcome)

    /** Only the closed event vocabulary and numeric attempt cross this boundary. */
    fun navigationGps(event: NavigationGpsEvent, attempt: Int) =
        enqueue(PlacesSeam.NAVIGATION_GPS, event.failed, event, attempt.coerceAtLeast(0))

    private fun enqueue(
        seam: PlacesSeam,
        failure: Boolean,
        gpsEvent: NavigationGpsEvent? = null,
        attempt: Int = 0,
        routingOutcome: RoutingInitOutcome? = null,
    ) {
        val observed = System.currentTimeMillis()
        try {
            writer.execute {
                val row = state.optJSONObject(seam.name) ?: JSONObject()
                if (routingOutcome != null) row.put("last_outcome", routingOutcome.name)
                if (gpsEvent != null) {
                    row.put("last_outcome", gpsEvent.name)
                    row.put("last_attempt", attempt)
                }
                if (failure) {
                    row.put("failures", row.optLong("failures", 0L) + 1L)
                    row.put("last_failure_ms", observed)
                    row.put("last_error", "seam_failed")
                    witness(seam.name, "not-joined")
                } else row.put("last_success_ms", observed)
                state.put(seam.name, row)
                state.put("suppressed_health_events", suppressed.get())
                persist()
            }
        } catch (_: RejectedExecutionException) {
            suppressed.incrementAndGet()
            witness("health-queue", "partial-function")
        }
    }

    private fun witness(seam: String, kind: String) {
        // Fixed internal seam names bound cardinality; a noisy seam cannot hide a new seam.
        if (
            emitted.computeIfAbsent("$seam/$kind") { AtomicInteger() }.getAndIncrement() < MAX_LINES
        )
            Log.e(TAG, "component=places seam=$seam class=$kind")
    }

    private fun persist() {
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(state.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (_: Exception) {
            file.failWrite(output)
            witness("health-write", "wrong-path")
        }
    }

    companion object {
        private const val TAG = "PlacesHealth"
        private const val MAX_LINES = 5
        private const val MAX_STATE_BYTES = 16_384L
    }
}
