package au.com.ausroads.core.geo

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.time.Duration
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Test

class SunCalcTest {

    // Adelaide CBD, WGS84.
    private val adelaideLat = -34.9285
    private val adelaideLon = 138.6007

    // Reference (sunrise-sunset.org NOAA-based API), Adelaide 2025-12-21, all UTC:
    //   sunrise   2025-12-20T19:26:55Z
    //   sunset    2025-12-21T10:00:15Z
    //   solarnoon 2025-12-21T02:43:35Z
    private val solsticeDate = LocalDate(2025, 12, 21)
    private val refSunrise = Instant.parse("2025-12-20T19:26:55Z")
    private val refSunset = Instant.parse("2025-12-21T10:00:15Z")
    private val refSolarNoon = Instant.parse("2025-12-21T02:43:35Z")

    private fun minutesBetween(a: Instant, b: Instant): Double =
        abs((a - b).inWholeSeconds) / 60.0

    @Test
    fun sunrise_adelaideSolstice_within5Minutes() {
        val sunrise = SunCalc.sunriseUtc(solsticeDate, adelaideLat, adelaideLon)
        assertThat(sunrise).isNotNull()
        assertThat(minutesBetween(requireNotNull(sunrise), refSunrise)).isLessThan(5.0)
    }

    @Test
    fun sunset_adelaideSolstice_within5Minutes() {
        val sunset = SunCalc.sunsetUtc(solsticeDate, adelaideLat, adelaideLon)
        assertThat(sunset).isNotNull()
        assertThat(minutesBetween(requireNotNull(sunset), refSunset)).isLessThan(5.0)
    }

    @Test
    fun solarNoon_adelaideSolstice_within5Minutes() {
        val noon = SunCalc.solarNoonUtc(solsticeDate, adelaideLat, adelaideLon)
        assertThat(noon).isNotNull()
        assertThat(minutesBetween(requireNotNull(noon), refSolarNoon)).isLessThan(5.0)
    }

    @Test
    fun solarNoon_isBetweenSunriseAndSunset() {
        val times = SunCalc.solarTimes(solsticeDate, adelaideLat, adelaideLon)
        val sunrise = requireNotNull(times.sunriseUtc)
        val noon = requireNotNull(times.solarNoonUtc)
        val sunset = requireNotNull(times.sunsetUtc)
        assertThat(sunrise < noon).isTrue()
        assertThat(noon < sunset).isTrue()
    }

    @Test
    fun dayLength_adelaideSolstice_isAboutFourteenAndAHalfHours() {
        // Reference day length 52400 s ≈ 14h33m. Wide tolerance (±10 min).
        val times = SunCalc.solarTimes(solsticeDate, adelaideLat, adelaideLon)
        val sunrise = requireNotNull(times.sunriseUtc)
        val sunset = requireNotNull(times.sunsetUtc)
        val lengthSeconds = (sunset - sunrise).inWholeSeconds
        assertThat(abs(lengthSeconds - 52_400L)).isLessThan(600L)
    }

    @Test
    fun polarNight_antarcticWinterSouthPoleRegion_noSunrise() {
        // ~-80°S in mid-June (southern winter): sun stays below the horizon -> null.
        val midWinter = LocalDate(2025, 6, 21)
        val times = SunCalc.solarTimes(midWinter, -80.0, 0.0)
        assertThat(times.sunriseUtc).isNull()
        assertThat(times.sunsetUtc).isNull()
        // Solar noon is still defined even when the sun never rises.
        assertThat(times.solarNoonUtc).isNotNull()
    }

    @Test
    fun polarDay_arcticSummerHighLatitude_noSunset() {
        // ~80°N at northern summer solstice: midnight sun -> no sunrise/sunset events.
        val midSummer = LocalDate(2025, 6, 21)
        val times = SunCalc.solarTimes(midSummer, 80.0, 0.0)
        assertThat(times.sunriseUtc).isNull()
        assertThat(times.sunsetUtc).isNull()
    }

    @Test
    fun daylightRemaining_beforeSunrise_isZero() {
        // One hour before sunrise.
        val before = refSunrise - Duration.parse("1h")
        val remaining = SunCalc.daylightRemaining(before, adelaideLat, adelaideLon, solsticeDate)
        assertThat(remaining).isEqualTo(Duration.ZERO)
    }

    @Test
    fun daylightRemaining_afterSunset_isZero() {
        val after = refSunset + Duration.parse("1h")
        val remaining = SunCalc.daylightRemaining(after, adelaideLat, adelaideLon, solsticeDate)
        assertThat(remaining).isEqualTo(Duration.ZERO)
    }

    @Test
    fun daylightRemaining_decreasesThroughTheDay() {
        // Two instants in daylight: later one must have strictly less daylight left.
        val times = SunCalc.solarTimes(solsticeDate, adelaideLat, adelaideLon)
        val sunrise = requireNotNull(times.sunriseUtc)
        val midday = requireNotNull(times.solarNoonUtc)
        val earlier = sunrise + Duration.parse("1h")
        val remEarlier = SunCalc.daylightRemaining(earlier, adelaideLat, adelaideLon, solsticeDate)
        val remLater = SunCalc.daylightRemaining(midday, adelaideLat, adelaideLon, solsticeDate)
        assertThat(remEarlier > remLater).isTrue()
        assertThat(remEarlier > Duration.ZERO).isTrue()
    }

    @Test
    fun daylightRemaining_atSunrise_equalsFullDayLength() {
        val times = SunCalc.solarTimes(solsticeDate, adelaideLat, adelaideLon)
        val sunrise = requireNotNull(times.sunriseUtc)
        val sunset = requireNotNull(times.sunsetUtc)
        val remaining = SunCalc.daylightRemaining(sunrise, adelaideLat, adelaideLon, solsticeDate)
        // At exactly sunrise, remaining daylight == sunset - sunrise (the whole day).
        val expected = sunset - sunrise
        assertThat(abs((remaining - expected).inWholeSeconds)).isLessThan(2L)
    }

    @Test
    fun daylightRemaining_polarNight_isZero() {
        val midWinter = LocalDate(2025, 6, 21)
        // Any instant on that day at -80°S returns zero (no daylight at all).
        val noonish = Instant.parse("2025-06-21T12:00:00Z")
        val remaining = SunCalc.daylightRemaining(noonish, -80.0, 0.0, midWinter)
        assertThat(remaining).isEqualTo(Duration.ZERO)
    }

    @Test
    fun daylightRemaining_australianMorning_handlesUtcDateDivergence() {
        // Adelaide (UTC+9:30): local 08:00 on 2026-06-21 == 2026-06-20T22:30:00Z, so a
        // caller's UTC-derived date (June 20) differs from the local solar day (June 21).
        // Daylight remaining must be a large positive value, not zero (regression: the
        // morning previously returned 0h because the UTC date's sunset was already past).
        val now = Instant.parse("2026-06-20T22:30:00Z")
        val utcDate = LocalDate(2026, 6, 20)
        val remaining = SunCalc.daylightRemaining(now, adelaideLat, adelaideLon, utcDate)
        assertThat(remaining > Duration.parse("6h")).isTrue()
        assertThat(remaining < Duration.parse("12h")).isTrue()
    }
}
