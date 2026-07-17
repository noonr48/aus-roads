package au.com.ausroads.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Closes the gap between an initial-fix timeout and a stream that later falls silent. */
internal class LocationStreamWatchdog(
    private val scope: CoroutineScope,
    private val onTimeout: () -> Unit,
) {
    private var timeoutJob: Job? = null

    fun arm(timeoutMillis: Long) {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            onTimeout()
        }
    }

    fun cancel() {
        timeoutJob?.cancel()
        timeoutJob = null
    }
}
