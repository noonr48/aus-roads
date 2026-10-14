package au.com.ausroads.ui.map

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.coroutines.resume

private const val STYLE_LOAD_TIMEOUT_MS = 15_000L
private val styleLoadIds = AtomicInteger()

/** Native callbacks only complete their still-owned coroutine, never mutate Compose state. */
internal suspend fun MapLibreMap.awaitPreparedStyle(json: String): Style =
    withContext(Dispatchers.Main.immediate) {
        currentCoroutineContext().ensureActive()
        val id = styleLoadIds.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        fun witness(stage: String) {
            // Fixed seam metadata only: never JSON, paths, features or exception payloads.
            Log.i("MapStyleLoad", "component=map seam=style-load id=$id stage=$stage " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - started} " +
                "dispatcher=${Dispatchers.Main.immediate.javaClass.simpleName}")
        }
        witness("start")
        val result = withTimeoutOrNull(STYLE_LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine<Style> { continuation ->
                setStyle(Style.Builder().fromJson(json)) { style ->
                    witness(if (continuation.isActive) "native-loaded" else "late-callback")
                    if (continuation.isActive) continuation.resume(style)
                }
            }
        }
        witness(if (result != null) "complete" else "timeout")
        result ?: error("component=map seam=style-load class=not-joined")
    }
