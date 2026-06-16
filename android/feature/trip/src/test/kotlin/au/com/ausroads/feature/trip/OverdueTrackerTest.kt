package au.com.ausroads.feature.trip

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import org.junit.Test

class OverdueTrackerTest {

    private val eta = Instant.parse("2026-06-13T08:30:00Z")

    // Defaults: graceMinutes = 30, dueSoonMinutes = 15.
    private fun checkIn(arrived: Boolean = false) = CheckIn(plannedEtaUtc = eta, arrived = arrived)

    @Test
    fun onTime_wellBeforeDueSoonWindow() {
        val now = eta - 60.minutes // 60 min before ETA, before the 15-min due-soon lead.
        assertThat(OverdueTracker.stateAt(checkIn(), now)).isEqualTo(OverdueState.ON_TIME)
    }

    @Test
    fun dueSoon_justInsideLeadWindow() {
        val now = eta - 10.minutes // within [eta-15, eta+30].
        assertThat(OverdueTracker.stateAt(checkIn(), now)).isEqualTo(OverdueState.DUE_SOON)
    }

    @Test
    fun dueSoon_atEtaItself() {
        assertThat(OverdueTracker.stateAt(checkIn(), eta)).isEqualTo(OverdueState.DUE_SOON)
    }

    @Test
    fun dueSoon_withinGraceAfterEta() {
        val now = eta + 20.minutes // past ETA but inside the 30-min grace.
        assertThat(OverdueTracker.stateAt(checkIn(), now)).isEqualTo(OverdueState.DUE_SOON)
    }

    @Test
    fun overdue_pastGrace() {
        val now = eta + 31.minutes // 1 min past the grace period.
        assertThat(OverdueTracker.stateAt(checkIn(), now)).isEqualTo(OverdueState.OVERDUE)
    }

    @Test
    fun boundary_exactlyAtDueSoonStart_isDueSoon() {
        // now == eta - dueSoon: NOT < dueSoonStart, so DUE_SOON (lower bound inclusive).
        val now = eta - 15.minutes
        assertThat(OverdueTracker.stateAt(checkIn(), now)).isEqualTo(OverdueState.DUE_SOON)
    }

    @Test
    fun boundary_oneInstantBeforeDueSoonStart_isOnTime() {
        val now = eta - 15.minutes - 1.minutes
        assertThat(OverdueTracker.stateAt(checkIn(), now)).isEqualTo(OverdueState.ON_TIME)
    }

    @Test
    fun boundary_exactlyAtGraceEnd_isDueSoon() {
        // now == eta + grace: NOT > overdueAfter, so still DUE_SOON (upper bound inclusive).
        val now = eta + 30.minutes
        assertThat(OverdueTracker.stateAt(checkIn(), now)).isEqualTo(OverdueState.DUE_SOON)
    }

    @Test
    fun boundary_oneInstantAfterGraceEnd_isOverdue() {
        val now = eta + 30.minutes + 1.minutes
        assertThat(OverdueTracker.stateAt(checkIn(), now)).isEqualTo(OverdueState.OVERDUE)
    }

    @Test
    fun arrived_overridesOnTime() {
        val now = eta - 60.minutes
        assertThat(OverdueTracker.stateAt(checkIn(arrived = true), now)).isEqualTo(OverdueState.ARRIVED)
    }

    @Test
    fun arrived_overridesOverdue() {
        val now = eta + 120.minutes // would be OVERDUE if not arrived.
        assertThat(OverdueTracker.stateAt(checkIn(arrived = true), now)).isEqualTo(OverdueState.ARRIVED)
    }

    @Test
    fun customWindows_respected() {
        // grace 0, dueSoon 5: due-soon window is [eta-5, eta]; anything after eta is overdue.
        val ci = CheckIn(plannedEtaUtc = eta, graceMinutes = 0, dueSoonMinutes = 5)
        assertThat(OverdueTracker.stateAt(ci, eta - 6.minutes)).isEqualTo(OverdueState.ON_TIME)
        assertThat(OverdueTracker.stateAt(ci, eta - 5.minutes)).isEqualTo(OverdueState.DUE_SOON)
        assertThat(OverdueTracker.stateAt(ci, eta)).isEqualTo(OverdueState.DUE_SOON)
        assertThat(OverdueTracker.stateAt(ci, eta + 1.minutes)).isEqualTo(OverdueState.OVERDUE)
    }

    @Test
    fun negativeWindows_clampedToZero_noInversion() {
        // Defensive: negative minutes coerce to 0 -> window collapses to the ETA instant.
        val ci = CheckIn(plannedEtaUtc = eta, graceMinutes = -10, dueSoonMinutes = -10)
        assertThat(OverdueTracker.stateAt(ci, eta - 1.minutes)).isEqualTo(OverdueState.ON_TIME)
        assertThat(OverdueTracker.stateAt(ci, eta)).isEqualTo(OverdueState.DUE_SOON)
        assertThat(OverdueTracker.stateAt(ci, eta + 1.minutes)).isEqualTo(OverdueState.OVERDUE)
    }
}
