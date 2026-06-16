/*
 * Solar position: sunrise, sunset, solar noon and remaining daylight for a date and
 * location, using the NOAA Solar Calculator algorithm (the equations published with
 * the NOAA ESRL Solar Calculator spreadsheet, after Meeus, "Astronomical Algorithms").
 *
 * Pure JVM math + kotlinx.datetime for the Instant/LocalDate types. No Android.
 */
package au.com.ausroads.core.geo

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.time.Duration

/**
 * The solar events for a single calendar day (UTC clock), at a fixed location.
 *
 * Any field is `null` when the event does not occur that day at that latitude
 * (polar day or polar night) — e.g. the sun never rises above the horizon.
 */
data class SolarTimes(
    val sunriseUtc: Instant?,
    val sunsetUtc: Instant?,
    val solarNoonUtc: Instant?,
)

/**
 * NOAA solar-position calculator.
 *
 * The sunrise/sunset solution uses the standard geometric horizon altitude of
 * -0.833° (sun's apparent radius + standard atmospheric refraction).
 */
object SunCalc {

    /** Standard solar-disc + refraction altitude for sunrise/sunset, in degrees. */
    private const val SUNRISE_ZENITH_DEG = 90.833

    /**
     * Compute sunrise/sunset/solar-noon (all in UTC) for [date] at [latitude]/[longitude]
     * (degrees, longitude positive east).
     *
     * Returns nulls for sunrise/sunset when the sun stays entirely above or below the
     * horizon for the whole day; solar noon is always defined.
     */
    fun solarTimes(date: LocalDate, latitude: Double, longitude: Double): SolarTimes {
        // Julian day at 00:00 UTC of the given date.
        val jdMidnight = julianDay(date.year, date.monthNumber, date.dayOfMonth)

        // Solar noon (minutes from UTC midnight) — iterate once for the equation of time.
        val noonMinutes = solarNoonMinutes(jdMidnight, longitude)
        val solarNoon = instantAtMinutes(date, noonMinutes)

        // Hour angle of sunrise; null when |cos H| > 1 (no rise/set that day).
        val haDeg = sunriseHourAngleDeg(jdMidnight, noonMinutes, latitude)
        if (haDeg == null) {
            return SolarTimes(sunriseUtc = null, sunsetUtc = null, solarNoonUtc = solarNoon)
        }

        // Each degree of hour angle is 4 minutes of time.
        val riseMinutes = noonMinutes - haDeg * 4.0
        val setMinutes = noonMinutes + haDeg * 4.0
        return SolarTimes(
            sunriseUtc = instantAtMinutes(date, riseMinutes),
            sunsetUtc = instantAtMinutes(date, setMinutes),
            solarNoonUtc = solarNoon,
        )
    }

    /** Convenience: sunrise only (UTC), or null. */
    fun sunriseUtc(date: LocalDate, latitude: Double, longitude: Double): Instant? =
        solarTimes(date, latitude, longitude).sunriseUtc

    /** Convenience: sunset only (UTC), or null. */
    fun sunsetUtc(date: LocalDate, latitude: Double, longitude: Double): Instant? =
        solarTimes(date, latitude, longitude).sunsetUtc

    /** Convenience: solar noon (UTC). */
    fun solarNoonUtc(date: LocalDate, latitude: Double, longitude: Double): Instant? =
        solarTimes(date, latitude, longitude).solarNoonUtc

    /**
     * Daylight remaining at instant [now] at [latitude]/[longitude] — the time until
     * sunset of the daylight window that currently contains [now].
     *
     * [date] is only a HINT for which solar day to evaluate. At far-east/west longitudes
     * the UTC calendar date a caller derives from [now] can differ from the local solar
     * day, and a solar day's `[sunrise, sunset)` window can straddle a UTC midnight (e.g.
     * Australia, where a morning local instant is on the previous UTC date) — so we probe
     * `date-1 .. date+1` and use whichever day's window brackets [now]. Returns
     * [Duration.ZERO] at night (between a sunset and the next sunrise) and during polar
     * night/day.
     */
    fun daylightRemaining(
        now: Instant,
        latitude: Double,
        longitude: Double,
        date: LocalDate,
    ): Duration {
        for (offset in -1..1) {
            val times = solarTimes(date.plus(offset, DateTimeUnit.DAY), latitude, longitude)
            val sunrise = times.sunriseUtc ?: continue
            val sunset = times.sunsetUtc ?: continue
            if (now >= sunrise && now < sunset) {
                return sunset - now
            }
        }
        return Duration.ZERO
    }

    // --- NOAA core -----------------------------------------------------------

    /**
     * Julian day number at 00:00 UTC for a proleptic-Gregorian calendar date
     * (Meeus / Fliegel-Van Flandern, eq. 7-1). Returns the JD at midnight UTC.
     */
    fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floorDiv(y, 100)
        val b = 2 - a + floorDiv(a, 4)
        val jd = floorInt(365.25 * (y + 4716)) +
            floorInt(30.6001 * (m + 1)) +
            day + b - 1524.5
        return jd
    }

    // Julian centuries since J2000.0 for a given JD.
    private fun julianCentury(jd: Double): Double = (jd - 2_451_545.0) / 36525.0

    // Geometric mean longitude of the sun (deg, 0..360).
    private fun geomMeanLongSun(t: Double): Double {
        var l = 280.46646 + t * (36000.76983 + t * 0.0003032)
        l %= 360.0
        if (l < 0) l += 360.0
        return l
    }

    // Geometric mean anomaly of the sun (deg).
    private fun geomMeanAnomalySun(t: Double): Double =
        357.52911 + t * (35999.05029 - 0.0001537 * t)

    // Eccentricity of Earth's orbit.
    private fun eccentricity(t: Double): Double =
        0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    // Sun's equation of center (deg).
    private fun sunEquationOfCenter(t: Double): Double {
        val m = Math.toRadians(geomMeanAnomalySun(t))
        return sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2.0 * m) * (0.019993 - 0.000101 * t) +
            sin(3.0 * m) * 0.000289
    }

    // Sun's true longitude (deg).
    private fun sunTrueLong(t: Double): Double =
        geomMeanLongSun(t) + sunEquationOfCenter(t)

    // Sun's apparent longitude (deg).
    private fun sunApparentLong(t: Double): Double {
        val omega = 125.04 - 1934.136 * t
        return sunTrueLong(t) - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
    }

    // Mean obliquity of the ecliptic (deg).
    private fun meanObliquityOfEcliptic(t: Double): Double =
        23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0

    // Corrected obliquity (deg).
    private fun obliquityCorrection(t: Double): Double {
        val omega = 125.04 - 1934.136 * t
        return meanObliquityOfEcliptic(t) + 0.00256 * cos(Math.toRadians(omega))
    }

    // Sun declination (deg).
    private fun sunDeclination(t: Double): Double {
        val e = Math.toRadians(obliquityCorrection(t))
        val lambda = Math.toRadians(sunApparentLong(t))
        val sinDelta = sin(e) * sin(lambda)
        return Math.toDegrees(asin(sinDelta))
    }

    // Equation of time (minutes).
    private fun equationOfTime(t: Double): Double {
        val epsilon = Math.toRadians(obliquityCorrection(t))
        val l0 = Math.toRadians(geomMeanLongSun(t))
        val e = eccentricity(t)
        val m = Math.toRadians(geomMeanAnomalySun(t))
        val y = tan(epsilon / 2.0) * tan(epsilon / 2.0)

        val sin2l0 = sin(2.0 * l0)
        val sinM = sin(m)
        val cos2l0 = cos(2.0 * l0)
        val sin4l0 = sin(4.0 * l0)
        val sin2m = sin(2.0 * m)

        val etime = y * sin2l0 - 2.0 * e * sinM + 4.0 * e * y * sinM * cos2l0 -
            0.5 * y * y * sin4l0 - 1.25 * e * e * sin2m
        return Math.toDegrees(etime) * 4.0 // radians -> degrees -> minutes (×4)
    }

    /**
     * Solar noon in minutes from UTC midnight for the given midnight-JD and longitude.
     * Uses the equation of time evaluated near local noon.
     */
    private fun solarNoonMinutes(jdMidnight: Double, longitude: Double): Double {
        // Evaluate EoT at ~noon: JD of (midnight + 0.5 day) minus the longitude offset.
        val tNoonApprox = julianCentury(jdMidnight + 0.5 - longitude / 360.0)
        val eqTime = equationOfTime(tNoonApprox)
        return 720.0 - 4.0 * longitude - eqTime
    }

    /**
     * Sunrise hour angle in degrees (positive). Returns null when the sun does not
     * cross the sunrise/sunset altitude that day (|cos H| > 1).
     */
    private fun sunriseHourAngleDeg(
        jdMidnight: Double,
        noonMinutes: Double,
        latitude: Double,
    ): Double? {
        // Declination evaluated at solar noon for stability.
        val tNoon = julianCentury(jdMidnight + noonMinutes / 1440.0)
        val decl = Math.toRadians(sunDeclination(tNoon))
        val latRad = Math.toRadians(latitude)
        val zenith = Math.toRadians(SUNRISE_ZENITH_DEG)

        val cosH = (cos(zenith) - sin(latRad) * sin(decl)) / (cos(latRad) * cos(decl))
        if (cosH > 1.0 || cosH < -1.0) return null
        return Math.toDegrees(acos(cosH))
    }

    // --- time helpers --------------------------------------------------------

    /**
     * Build a UTC [Instant] at [minutesFromMidnight] minutes after 00:00 UTC of [date].
     * Minutes may be negative or exceed a day; the epoch arithmetic handles the carry,
     * so a sunrise just after UTC midnight or a sunset past it map to the right instant.
     */
    private fun instantAtMinutes(date: LocalDate, minutesFromMidnight: Double): Instant {
        val epochDayMillis = date.toEpochDays().toLong() * MILLIS_PER_DAY
        val offsetMillis = (minutesFromMidnight * 60_000.0).toLong()
        return Instant.fromEpochMilliseconds(epochDayMillis + offsetMillis)
    }

    private const val MILLIS_PER_DAY = 86_400_000L

    private fun floorInt(v: Double): Int = kotlin.math.floor(v).toInt()

    private fun floorDiv(a: Int, b: Int): Int = Math.floorDiv(a, b)
}
