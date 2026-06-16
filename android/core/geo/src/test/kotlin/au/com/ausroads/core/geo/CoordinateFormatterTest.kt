package au.com.ausroads.core.geo

import au.com.ausroads.core.model.GeoPoint
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoordinateFormatterTest {

    private fun point(lat: Double, lon: Double) = GeoPoint(longitude = lon, latitude = lat)

    // --- decimal degrees -----------------------------------------------------

    @Test
    fun decimalDegrees_formatsLatThenLonTo5dp() {
        val s = CoordinateFormatter.decimalDegrees(point(-34.9285, 138.6007))
        assertThat(s).isEqualTo("-34.92850, 138.60070")
    }

    @Test
    fun decimalDegrees_positiveAndZero() {
        assertThat(CoordinateFormatter.decimalDegrees(0.0, 0.0)).isEqualTo("0.00000, 0.00000")
        assertThat(CoordinateFormatter.decimalDegrees(1.5, 2.25)).isEqualTo("1.50000, 2.25000")
    }

    // --- DMS -----------------------------------------------------------------

    @Test
    fun dms_adelaide_matchesPublishedDms() {
        // Published: -34.9285,138.6007 => 34°55'42.6"S 138°36'02.5"E
        // (countrycoordinate.com lists 34°55'42.6"S, 138°36'02.7"E; seconds rounding
        // at 1 dp keeps us within the same value.)
        val s = CoordinateFormatter.dms(point(-34.9285, 138.6007))
        assertThat(s).startsWith("34°55'42.6\"S")
        assertThat(s).contains("138°36'02.5\"E")
    }

    @Test
    fun dms_hemisphereSigns() {
        // Northern + Western quadrant.
        val nw = CoordinateFormatter.dms(point(34.9285, -138.6007))
        assertThat(nw).contains("N")
        assertThat(nw).contains("W")
        // Southern + Eastern quadrant.
        val se = CoordinateFormatter.dms(point(-1.0, 1.0))
        assertThat(se).contains("S")
        assertThat(se).contains("E")
    }

    @Test
    fun dms_zeroPaddingOfMinutesAndSeconds() {
        // 138.6007E -> 36 minutes, 02.5 seconds -> "138°36'02.5\"E"
        val s = CoordinateFormatter.dms(0.0, 138.6007)
        assertThat(s).contains("138°36'02.5\"E")
    }

    // --- UTM (reference: Chris Veness Geodesy, movable-type.co.uk) ------------
    // 52.20°N, 0.12°E  =>  UTM 31 N 303189 5787193 ; MGRS 31U CT 03189 87193.

    @Test
    fun utm_referencePoint_zoneHemisphereAndCoords() {
        val u = CoordinateFormatter.utm(point(52.20, 0.12))
        assertThat(u.zone).isEqualTo(31)
        assertThat(u.hemisphere).isEqualTo('N')
        // Tolerance: a few metres covers series-truncation + reference rounding.
        assertThat(u.easting).isWithin(3.0).of(303189.0)
        assertThat(u.northing).isWithin(3.0).of(5787193.0)
    }

    @Test
    fun utm_onCentralMeridian_eastingIsFalseEasting() {
        // Zone 31 central meridian is 3°E. On it, easting == 500000 exactly, and at the
        // equator northing == 0 (analytic invariant of the projection).
        val u = CoordinateFormatter.utm(point(0.0, 3.0))
        assertThat(u.easting).isWithin(1e-3).of(500_000.0)
        assertThat(u.northing).isWithin(1e-3).of(0.0)
        assertThat(u.zone).isEqualTo(31)
    }

    @Test
    fun utm_southernHemisphere_appliesFalseNorthing() {
        // Adelaide is in zone 54, southern hemisphere -> northing near 6.13e6 (=10e6 -
        // distance south of equator). Reference (movable-type) ~ 6132280 mN.
        val u = CoordinateFormatter.utm(point(-34.9285, 138.6007))
        assertThat(u.zone).isEqualTo(54)
        assertThat(u.hemisphere).isEqualTo('S')
        // Southern false-northing means a large positive value.
        assertThat(u.northing).isGreaterThan(6_000_000.0)
        assertThat(u.northing).isLessThan(6_300_000.0)
        // Easting comfortably inside the zone band.
        assertThat(u.easting).isGreaterThan(100_000.0)
        assertThat(u.easting).isLessThan(900_000.0)
    }

    @Test
    fun utmString_hasZoneBandAndMetres() {
        val s = CoordinateFormatter.utmString(point(52.20, 0.12))
        // e.g. "31U 303189mE 5787193mN"
        assertThat(s).startsWith("31U ")
        assertThat(s).contains("mE ")
        assertThat(s).endsWith("mN")
    }

    @Test
    fun latitudeBand_knownBands() {
        // 52.20 -> U ; -34.93 -> H ; 0 -> N (band N spans 0..8) ; -80 -> C ; 80 -> X.
        assertThat(CoordinateFormatter.latitudeBand(52.20)).isEqualTo('U')
        assertThat(CoordinateFormatter.latitudeBand(-34.93)).isEqualTo('H')
        assertThat(CoordinateFormatter.latitudeBand(0.0)).isEqualTo('N')
        assertThat(CoordinateFormatter.latitudeBand(-80.0)).isEqualTo('C')
        assertThat(CoordinateFormatter.latitudeBand(80.0)).isEqualTo('X')
    }

    @Test
    fun latitudeBand_omitsIandO() {
        // Sweep all bands and ensure no 'I' or 'O' is produced.
        var lat = -80.0
        while (lat < 84.0) {
            val band = CoordinateFormatter.latitudeBand(lat)
            assertThat(band).isNotEqualTo('I')
            assertThat(band).isNotEqualTo('O')
            lat += 1.0
        }
    }

    // --- MGRS ----------------------------------------------------------------

    @Test
    fun mgrs_referencePoint_prefixExact_digitsWithinTolerance() {
        // 52.20°N,0.12°E => 31U CT 03189 87193 (compact: 31UCT0318987193).
        // The grid-zone designator, band and 100km square are deterministic; assert
        // them exactly. The 5-digit easting/northing-within-square are subject to a
        // few metres of series-truncation vs the reference, so compare numerically
        // with a small tolerance rather than as an exact string (MGRS truncates, so a
        // sub-metre difference can flip the last digit at a bucket boundary).
        val s = CoordinateFormatter.mgrs(point(52.20, 0.12), precision = 5)
        assertThat(s).hasLength(5 + 10)
        assertThat(s.substring(0, 5)).isEqualTo("31UCT")
        val east = s.substring(5, 10).toInt()
        val north = s.substring(10, 15).toInt()
        // reference easting-within-square 03189, northing 87193; allow ±3 m.
        assertThat(east).isAtLeast(3186)
        assertThat(east).isAtMost(3192)
        assertThat(north).isAtLeast(87190)
        assertThat(north).isAtMost(87196)
    }

    @Test
    fun mgrs_zoneBandAndSquarePrefix() {
        val s = CoordinateFormatter.mgrs(point(52.20, 0.12))
        assertThat(s).startsWith("31UCT")
    }

    @Test
    fun mgrs_precisionControlsDigitCount() {
        val p5 = CoordinateFormatter.mgrs(point(52.20, 0.12), precision = 5)
        val p3 = CoordinateFormatter.mgrs(point(52.20, 0.12), precision = 3)
        val p1 = CoordinateFormatter.mgrs(point(52.20, 0.12), precision = 1)
        // Prefix "31UCT" (5 chars) + 2*precision digits.
        assertThat(p5).hasLength(5 + 10)
        assertThat(p3).hasLength(5 + 6)
        assertThat(p1).hasLength(5 + 2)
        // Same 100km square prefix at every precision.
        assertThat(p3).startsWith("31UCT")
        assertThat(p1).startsWith("31UCT")
        // p3 is the leading-digit truncation of p5: easting "03189"->first 3 "031",
        // northing "87193"->first 3 "871".
        val p5East = p5.substring(5, 10)
        val p5North = p5.substring(10, 15)
        val p3East = p3.substring(5, 8)
        val p3North = p3.substring(8, 11)
        assertThat(p3East).isEqualTo(p5East.substring(0, 3))
        assertThat(p3North).isEqualTo(p5North.substring(0, 3))
    }

    @Test
    fun mgrs_invalidPrecision_throws() {
        try {
            CoordinateFormatter.mgrs(point(52.20, 0.12), precision = 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("precision")
        }
    }

    @Test
    fun mgrs_southernHemisphere_bandIsBelowN() {
        // Adelaide band H (southern). Just assert the band letter prefix is H and zone 54.
        val s = CoordinateFormatter.mgrs(point(-34.9285, 138.6007))
        assertThat(s).startsWith("54H")
    }
}
