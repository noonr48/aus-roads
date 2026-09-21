package au.com.ausroads.ui.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocationFixPolicyTest {
    @Test
    fun `two minute old fix is still fresh`() {
        assertThat(isLocationFixFresh(880_000L, 1_000_000L)).isTrue()
    }

    @Test
    fun `older cached fix is rejected`() {
        assertThat(isLocationFixFresh(879_999L, 1_000_000L)).isFalse()
    }

    @Test
    fun `approximate fix bridges Android coarse update cadence`() {
        val tenMinutesAgo = 400_000L

        assertThat(
            isLocationFixFresh(
                fixTimeMillis = tenMinutesAgo,
                nowMillis = 1_000_000L,
                maxAgeMillis = maximumLocationFixAgeMillis(isPreciseLocationGranted = false),
            ),
        ).isTrue()
        assertThat(
            isLocationFixFresh(
                fixTimeMillis = tenMinutesAgo,
                nowMillis = 1_000_000L,
                maxAgeMillis = maximumLocationFixAgeMillis(isPreciseLocationGranted = true),
            ),
        ).isFalse()
    }

    @Test
    fun `live approximate callback uses receipt time after freshness gate`() {
        assertThat(
            liveLocationDisplayTimeMillis(
                fixTimeMillis = 400_000L,
                receivedAtMillis = 1_000_000L,
                isPreciseLocationGranted = false,
            ),
        ).isEqualTo(1_000_000L)
    }

    @Test
    fun `live approximate callback still rejects an old source fix`() {
        assertThat(
            liveLocationDisplayTimeMillis(
                fixTimeMillis = 99_999L,
                receivedAtMillis = 1_000_000L,
                isPreciseLocationGranted = false,
            ),
        ).isNull()
    }

    @Test
    fun `live precise callback preserves its capture time`() {
        assertThat(
            liveLocationDisplayTimeMillis(
                fixTimeMillis = 999_000L,
                receivedAtMillis = 1_000_000L,
                isPreciseLocationGranted = true,
            ),
        ).isEqualTo(999_000L)
    }

    @Test
    fun `fix before manual refresh is rejected`() {
        assertThat(
            isLocationFixFresh(
                fixTimeMillis = 999_000L,
                nowMillis = 1_001_000L,
                minimumFixTimeMillis = 1_000_000L,
            ),
        ).isFalse()
    }

    @Test
    fun `recenter callback captured after request start is accepted`() {
        assertThat(
            isLocationFixFresh(
                fixTimeMillis = 1_000_001L,
                nowMillis = 1_000_010L,
                minimumFixTimeMillis = 1_000_000L,
            ),
        ).isTrue()
    }

    @Test
    fun `freshness expiry delay clears immediately after the accepted age boundary`() {
        assertThat(
            remainingLocationFreshnessMillis(
                fixTimeMillis = 880_000L,
                nowMillis = 1_000_000L,
            ),
        ).isEqualTo(1L)
        assertThat(
            remainingLocationFreshnessMillis(
                fixTimeMillis = 879_999L,
                nowMillis = 1_000_000L,
            ),
        ).isNull()
    }

    @Test
    fun `fresh last known fix wins over more accurate stale fix`() {
        val staleAccurate = TestFix(timeMillis = 800_000L, accuracyMeters = 5f)
        val freshLessAccurate = TestFix(timeMillis = 990_000L, accuracyMeters = 50f)

        val selected = selectBestFreshFix(
            candidates = listOf(staleAccurate, freshLessAccurate),
            nowMillis = 1_000_000L,
            fixTimeMillis = TestFix::timeMillis,
            accuracyMeters = TestFix::accuracyMeters,
        )

        assertThat(selected).isEqualTo(freshLessAccurate)
    }

    @Test
    fun `known accuracy wins over unknown accuracy among fresh fixes`() {
        val unknownAccuracy = TestFix(timeMillis = 999_000L, accuracyMeters = null)
        val knownAccuracy = TestFix(timeMillis = 998_000L, accuracyMeters = 80f)

        val selected = selectBestFreshFix(
            candidates = listOf(unknownAccuracy, knownAccuracy),
            nowMillis = 1_000_000L,
            fixTimeMillis = TestFix::timeMillis,
            accuracyMeters = TestFix::accuracyMeters,
        )

        assertThat(selected).isEqualTo(knownAccuracy)
    }

    @Test
    fun `recenter owner cancels replaced request before starting its successor`() {
        val events = mutableListOf<String>()
        val owner = RecenterRequestOwner()
        owner.replace {
            events += "first-started"
            RecenterRequest { events += "first-cancelled" }
        }

        owner.replace {
            events += "second-started"
            RecenterRequest { events += "second-cancelled" }
        }
        owner.cancel()
        owner.cancel()

        assertThat(events).containsExactly(
            "first-started",
            "first-cancelled",
            "second-started",
            "second-cancelled",
        ).inOrder()
    }
}

private data class TestFix(
    val timeMillis: Long,
    val accuracyMeters: Float?,
)
