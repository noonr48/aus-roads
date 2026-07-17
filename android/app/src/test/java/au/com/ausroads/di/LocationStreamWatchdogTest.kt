package au.com.ausroads.di

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationStreamWatchdogTest {
    @Test
    fun `watchdog fires when its deadline passes`() = runTest {
        var timeoutCount = 0
        val watchdog = LocationStreamWatchdog(this) { timeoutCount++ }

        watchdog.arm(timeoutMillis = 1_000L)
        advanceTimeBy(999L)
        runCurrent()
        assertThat(timeoutCount).isEqualTo(0)

        advanceTimeBy(1L)
        runCurrent()
        assertThat(timeoutCount).isEqualTo(1)
    }

    @Test
    fun `rearming watchdog postpones timeout from the latest fix`() = runTest {
        var timeoutCount = 0
        val watchdog = LocationStreamWatchdog(this) { timeoutCount++ }

        watchdog.arm(timeoutMillis = 1_000L)
        advanceTimeBy(800L)
        watchdog.arm(timeoutMillis = 1_000L)
        advanceTimeBy(999L)
        runCurrent()
        assertThat(timeoutCount).isEqualTo(0)

        advanceTimeBy(1L)
        runCurrent()
        assertThat(timeoutCount).isEqualTo(1)
    }

    @Test
    fun `cancelling watchdog prevents timeout`() = runTest {
        var timeoutCount = 0
        val watchdog = LocationStreamWatchdog(this) { timeoutCount++ }

        watchdog.arm(timeoutMillis = 1_000L)
        watchdog.cancel()
        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(timeoutCount).isEqualTo(0)
    }
}
