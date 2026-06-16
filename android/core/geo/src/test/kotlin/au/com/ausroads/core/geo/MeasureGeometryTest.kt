package au.com.ausroads.core.geo

import au.com.ausroads.core.model.GeoPoint
import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import org.junit.Test

class MeasureGeometryTest {

    private fun point(lat: Double, lon: Double) = GeoPoint(longitude = lon, latitude = lat)

    @Test
    fun pathLength_emptyOrSingle_isZero() {
        assertThat(MeasureGeometry.pathLengthMeters(emptyList())).isEqualTo(0.0)
        assertThat(MeasureGeometry.pathLengthMeters(listOf(point(0.0, 0.0)))).isEqualTo(0.0)
    }

    @Test
    fun pathLength_oneDegreeOfLatitude_isAboutOneEleventhKm() {
        // One degree of latitude along a meridian ~= 111.2 km on the mean sphere.
        // Reference: 2*pi*R/360 = 2*pi*6371000/360 ≈ 111195 m.
        val d = MeasureGeometry.pathLengthMeters(
            listOf(point(0.0, 0.0), point(1.0, 0.0)),
        )
        assertThat(d).isWithin(500.0).of(111_195.0)
    }

    @Test
    fun pathLength_sumsLegs() {
        // Two equal 1° meridian legs should be ~twice a single leg.
        val single = MeasureGeometry.pathLengthMeters(listOf(point(0.0, 0.0), point(1.0, 0.0)))
        val doubleLeg = MeasureGeometry.pathLengthMeters(
            listOf(point(0.0, 0.0), point(1.0, 0.0), point(2.0, 0.0)),
        )
        assertThat(doubleLeg).isWithin(single * 0.01).of(single * 2.0)
    }

    @Test
    fun haversine_symmetry() {
        val a = point(-34.9285, 138.6007)
        val b = point(-37.8136, 144.9631)
        val ab = MeasureGeometry.haversineMeters(a, b)
        val ba = MeasureGeometry.haversineMeters(b, a)
        assertThat(ab).isWithin(1e-6).of(ba)
    }

    @Test
    fun haversine_adelaideToMelbourne_knownDistance() {
        // Great-circle Adelaide (-34.9285,138.6007) to Melbourne (-37.8136,144.9631).
        // Published great-circle distance ≈ 654 km. Wide tolerance.
        val d = MeasureGeometry.haversineMeters(
            point(-34.9285, 138.6007),
            point(-37.8136, 144.9631),
        )
        assertThat(d).isWithin(15_000.0).of(654_000.0)
    }

    @Test
    fun haversine_samePoint_isZero() {
        val a = point(10.0, 20.0)
        assertThat(MeasureGeometry.haversineMeters(a, a)).isWithin(1e-6).of(0.0)
    }

    @Test
    fun polygonArea_tooFewPoints_isZero() {
        assertThat(MeasureGeometry.polygonAreaSquareMeters(emptyList())).isEqualTo(0.0)
        assertThat(MeasureGeometry.polygonAreaSquareMeters(listOf(point(0.0, 0.0)))).isEqualTo(0.0)
        assertThat(
            MeasureGeometry.polygonAreaSquareMeters(listOf(point(0.0, 0.0), point(0.0, 1.0))),
        ).isEqualTo(0.0)
    }

    @Test
    fun polygonArea_oneDegreeSquareAtEquator_matchesAnalytic() {
        // A 1°x1° cell with its south edge on the equator: lon 0..1, lat 0..1.
        // Analytic spherical area of a lat/lon cell:
        //   R^2 * (lon2-lon1[rad]) * (sin(lat2)-sin(lat1)).
        val r = MeasureGeometry.EARTH_RADIUS_METERS
        val lonSpanRad = 1.0 * PI / 180.0
        val analytic = r * r * lonSpanRad * (Math.sin(Math.toRadians(1.0)) - Math.sin(0.0))

        val ring = listOf(
            point(0.0, 0.0),
            point(0.0, 1.0),
            point(1.0, 1.0),
            point(1.0, 0.0),
        )
        val area = MeasureGeometry.polygonAreaSquareMeters(ring)
        // Within 0.5% of the analytic cell area (~1.23e10 m^2).
        assertThat(area).isWithin(analytic * 0.005).of(analytic)
    }

    @Test
    fun polygonArea_orientationIndependent() {
        val cw = listOf(
            point(0.0, 0.0),
            point(0.0, 1.0),
            point(1.0, 1.0),
            point(1.0, 0.0),
        )
        val ccw = cw.reversed()
        val areaCw = MeasureGeometry.polygonAreaSquareMeters(cw)
        val areaCcw = MeasureGeometry.polygonAreaSquareMeters(ccw)
        assertThat(areaCw).isWithin(areaCw * 1e-6).of(areaCcw)
        assertThat(areaCw).isGreaterThan(0.0)
    }
}
