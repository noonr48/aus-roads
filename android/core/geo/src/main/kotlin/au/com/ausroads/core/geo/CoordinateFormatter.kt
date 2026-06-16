/*
 * Coordinate formatting for a WGS84 point: decimal degrees, degrees-minutes-seconds,
 * UTM (transverse-Mercator forward) and MGRS (military grid reference system).
 *
 * Pure JVM math — no Android, no external geodesy library. The UTM forward uses the
 * standard USGS series expansion (Snyder, "Map Projections — A Working Manual",
 * USGS Professional Paper 1395, 1987, eqs. 8-9..8-15); MGRS letter assignment follows
 * the NGA/DMA TM 8358.1 100 km square scheme (AA / AN lettering origins).
 */
package au.com.ausroads.core.geo

import au.com.ausroads.core.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A point projected into the Universal Transverse Mercator grid.
 *
 * @property zone UTM longitude zone, 1..60.
 * @property hemisphere 'N' for northern, 'S' for southern.
 * @property easting metres east within the zone (false-easting 500000 applied).
 * @property northing metres north (false-northing 10000000 applied in the south).
 */
data class Utm(
    val zone: Int,
    val hemisphere: Char,
    val easting: Double,
    val northing: Double,
)

/**
 * Formats a geographic position in several human / grid notations.
 *
 * All entry points accept either a [GeoPoint] or raw `latitude`/`longitude` doubles
 * (degrees). Longitude is normalised into -180..180 for zone computation.
 */
object CoordinateFormatter {

    // --- WGS84 ellipsoid constants ---
    private const val WGS84_A = 6_378_137.0 // semi-major axis (m)
    private const val WGS84_F = 1.0 / 298.257_223_563 // flattening
    private const val K0 = 0.9996 // UTM scale factor on the central meridian
    private const val FALSE_EASTING = 500_000.0
    private const val FALSE_NORTHING_SOUTH = 10_000_000.0

    // --- decimal degrees -----------------------------------------------------

    /** Decimal degrees, longitude then latitude is NOT used here — geographic order is
     * "lat, lon"; e.g. `-34.92850, 138.60070` (5 decimal places). */
    fun decimalDegrees(point: GeoPoint): String =
        decimalDegrees(point.latitude, point.longitude)

    /** Decimal degrees `"<lat>, <lon>"` to 5 dp. */
    fun decimalDegrees(latitude: Double, longitude: Double): String {
        return "${format5(latitude)}, ${format5(longitude)}"
    }

    // --- degrees / minutes / seconds ----------------------------------------

    /**
     * Degrees-minutes-seconds with hemisphere letters, e.g.
     * `34°55'42.6"S 138°36'02.5"E`. Seconds are shown to 1 dp, minutes/degrees
     * zero-padded to two digits.
     */
    fun dms(point: GeoPoint): String = dms(point.latitude, point.longitude)

    /** DMS for raw degrees. */
    fun dms(latitude: Double, longitude: Double): String {
        val latPart = dmsComponent(latitude, isLatitude = true)
        val lonPart = dmsComponent(longitude, isLatitude = false)
        return "$latPart $lonPart"
    }

    private fun dmsComponent(value: Double, isLatitude: Boolean): String {
        val hemisphere = if (isLatitude) {
            if (value < 0) 'S' else 'N'
        } else {
            if (value < 0) 'W' else 'E'
        }
        var seconds = abs(value) * 3600.0
        var degrees = floor(seconds / 3600.0).toInt()
        seconds -= degrees * 3600.0
        var minutes = floor(seconds / 60.0).toInt()
        seconds -= minutes * 60.0
        // Guard floating-point rounding that could push seconds to 60.0 at 1 dp.
        if (roundTo1(seconds) >= 60.0) {
            seconds = 0.0
            minutes += 1
            if (minutes >= 60) {
                minutes = 0
                degrees += 1
            }
        }
        val secStr = pad2Seconds(seconds)
        return "$degrees°${pad2(minutes)}'$secStr\"$hemisphere"
    }

    // --- UTM -----------------------------------------------------------------

    /** Project [point] to UTM. */
    fun utm(point: GeoPoint): Utm = utm(point.latitude, point.longitude)

    /**
     * Project raw `latitude`/`longitude` degrees to UTM via the USGS transverse-Mercator
     * forward series. Valid for the standard UTM band (-80..84 lat); outside that the
     * result still computes but is not meaningful.
     */
    fun utm(latitude: Double, longitude: Double): Utm {
        val lonNorm = normalizeLongitude(longitude)
        val zone = utmZone(latitude, lonNorm)
        val lonOrigin = (zone - 1) * 6.0 - 180.0 + 3.0 // central meridian of the zone
        val latRad = Math.toRadians(latitude)
        val lonRad = Math.toRadians(lonNorm)
        val lonOriginRad = Math.toRadians(lonOrigin)

        val eSq = WGS84_F * (2.0 - WGS84_F) // first eccentricity squared
        val ePrimeSq = eSq / (1.0 - eSq)

        val n = WGS84_A / sqrt(1.0 - eSq * sin(latRad) * sin(latRad))
        val t = tan(latRad) * tan(latRad)
        val c = ePrimeSq * cos(latRad) * cos(latRad)
        val a = cos(latRad) * (lonRad - lonOriginRad)

        val m = meridionalArc(latRad, eSq)

        val easting = K0 * n * (
            a + (1.0 - t + c) * a * a * a / 6.0 +
                (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ePrimeSq) * a * a * a * a * a / 120.0
            ) + FALSE_EASTING

        var northing = K0 * (
            m + n * tan(latRad) * (
                a * a / 2.0 +
                    (5.0 - t + 9.0 * c + 4.0 * c * c) * a * a * a * a / 24.0 +
                    (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ePrimeSq) *
                    a * a * a * a * a * a / 720.0
                )
            )

        val hemisphere = if (latitude < 0) 'S' else 'N'
        if (hemisphere == 'S') northing += FALSE_NORTHING_SOUTH

        return Utm(zone = zone, hemisphere = hemisphere, easting = easting, northing = northing)
    }

    /**
     * UTM as a string, e.g. `54H 280000mE 6133000mN` — zone + latitude band letter,
     * then easting / northing rounded to whole metres.
     */
    fun utmString(point: GeoPoint): String = utmString(point.latitude, point.longitude)

    /** UTM string for raw degrees. */
    fun utmString(latitude: Double, longitude: Double): String {
        val u = utm(latitude, longitude)
        val band = latitudeBand(latitude)
        val e = u.easting.roundToLong()
        val north = u.northing.roundToLong()
        return "${u.zone}$band ${e}mE ${north}mN"
    }

    // Meridional arc length M for the given latitude (USGS eq. 3-21).
    private fun meridionalArc(latRad: Double, eSq: Double): Double {
        val e2 = eSq
        val e4 = e2 * e2
        val e6 = e4 * e2
        return WGS84_A * (
            (1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0) * latRad -
                (3.0 * e2 / 8.0 + 3.0 * e4 / 32.0 + 45.0 * e6 / 1024.0) * sin(2.0 * latRad) +
                (15.0 * e4 / 256.0 + 45.0 * e6 / 1024.0) * sin(4.0 * latRad) -
                (35.0 * e6 / 3072.0) * sin(6.0 * latRad)
            )
    }

    /** UTM longitude zone for a position, including the Norway/Svalbard exceptions. */
    fun utmZone(latitude: Double, longitude: Double): Int {
        val lon = normalizeLongitude(longitude)
        var zone = floor((lon + 180.0) / 6.0).toInt() + 1
        if (zone > 60) zone = 60
        if (zone < 1) zone = 1
        // Norway exception: zone 32 is widened for southern Norway.
        if (latitude in 56.0..64.0 && lon in 3.0..12.0) {
            zone = 32
        }
        // Svalbard exceptions.
        if (latitude in 72.0..84.0) {
            zone = when {
                lon in 0.0..9.0 -> 31
                lon in 9.0..21.0 -> 33
                lon in 21.0..33.0 -> 35
                lon in 33.0..42.0 -> 37
                else -> zone
            }
        }
        return zone
    }

    // --- MGRS ----------------------------------------------------------------

    /**
     * MGRS / USNG grid reference, e.g. `54H VK 80000 33000` rendered compactly as
     * `54HVK8000033000` at the default 5-digit precision.
     *
     * @param precision number of digits per easting/northing (1..5); 5 ⇒ 1 m resolution.
     */
    fun mgrs(point: GeoPoint, precision: Int = 5): String =
        mgrs(point.latitude, point.longitude, precision)

    /** MGRS for raw degrees. */
    fun mgrs(latitude: Double, longitude: Double, precision: Int = 5): String {
        require(precision in 1..5) { "precision must be 1..5: $precision" }
        val u = utm(latitude, longitude)
        val band = latitudeBand(latitude)
        val (colLetter, rowLetter) = hundredKmSquare(u.zone, u.easting, u.northing, latitude)

        // Easting/northing within the 100 km square, truncated to `precision` digits.
        val divisor = pow10(5 - precision)
        val eastWithin = (u.easting % 100_000.0).toLong()
        val northWithin = (u.northing % 100_000.0).toLong()
        val eastDigits = padLeftZeros(eastWithin / divisor, precision)
        val northDigits = padLeftZeros(northWithin / divisor, precision)

        return "${u.zone}$band$colLetter$rowLetter$eastDigits$northDigits"
    }

    /**
     * Determine the 100 km grid square column/row letters for a UTM coordinate.
     * Column letters cycle A-Z (omitting I, O) in three 8-letter sets keyed by zone
     * modulo 3; row letters cycle A-V (omitting I, O) and alternate the starting
     * letter on even zones. (NGA TM 8358.1, AA lettering scheme.)
     */
    private fun hundredKmSquare(
        zone: Int,
        easting: Double,
        northing: Double,
        latitude: Double,
    ): Pair<Char, Char> {
        val colLetters = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
        val set = (zone - 1) % 3
        // Easting 100 km index: easting 100000..800000 ⇒ index 0..7 within the set.
        val colIndex = (floor(easting / 100_000.0).toInt() - 1).coerceIn(0, 7)
        val colLetter = colLetters[set][colIndex]

        // Row lettering: full A-V (20 letters) sequence, offset by zone parity.
        val rowLettersEven = "FGHJKLMNPQRSTUVABCDE" // even zones start at 'F'
        val rowLettersOdd = "ABCDEFGHJKLMNPQRSTUV" // odd zones start at 'A'
        val rowSeq = if (zone % 2 == 0) rowLettersEven else rowLettersOdd
        // Northing repeats every 2000 km (20 * 100 km); take metres mod 2,000,000.
        val northMod = ((northing % 2_000_000.0) + 2_000_000.0) % 2_000_000.0
        val rowIndex = floor(northMod / 100_000.0).toInt().coerceIn(0, rowSeq.length - 1)
        val rowLetter = rowSeq[rowIndex]
        return colLetter to rowLetter
    }

    // --- latitude band -------------------------------------------------------

    /**
     * MGRS/UTM latitude band letter (C..X, omitting I and O) for the given latitude.
     * Bands are 8° tall from -80°; band X (72..84) is 12° tall. Latitudes outside
     * -80..84 are clamped to the nearest valid band.
     */
    fun latitudeBand(latitude: Double): Char {
        val bands = "CDEFGHJKLMNPQRSTUVWX"
        val clamped = latitude.coerceIn(-80.0, 83.999)
        var index = floor((clamped + 80.0) / 8.0).toInt()
        if (index >= bands.length) index = bands.length - 1
        if (index < 0) index = 0
        return bands[index]
    }

    // --- helpers -------------------------------------------------------------

    private fun normalizeLongitude(longitude: Double): Double {
        var lon = longitude % 360.0
        if (lon > 180.0) lon -= 360.0
        if (lon < -180.0) lon += 360.0
        return lon
    }

    private fun pow10(p: Int): Long {
        var r = 1L
        repeat(p) { r *= 10L }
        return r
    }

    private fun padLeftZeros(value: Long, width: Int): String {
        val s = value.toString()
        if (s.length >= width) return s.takeLast(width)
        return "0".repeat(width - s.length) + s
    }

    private fun format5(v: Double): String {
        // Fixed 5-dp formatting without locale surprises (always '.' decimal).
        val scaled = (v * 100_000.0).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val absScaled = abs(scaled)
        val whole = absScaled / 100_000L
        val frac = absScaled % 100_000L
        val fracStr = frac.toString().padStart(5, '0')
        return "$sign$whole.$fracStr"
    }

    private fun roundTo1(v: Double): Double = (v * 10.0).roundToLong() / 10.0

    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    private fun pad2Seconds(seconds: Double): String {
        val rounded = roundTo1(seconds)
        // Two integer digits, one decimal: e.g. 2.5 -> "02.5", 42.6 -> "42.6".
        val whole = floor(rounded).toInt()
        val frac = ((rounded - whole) * 10.0).roundToLong()
        return "${pad2(whole)}.${frac}"
    }
}
