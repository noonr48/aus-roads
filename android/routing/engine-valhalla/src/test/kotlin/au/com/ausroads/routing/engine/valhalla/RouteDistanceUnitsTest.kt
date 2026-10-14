package au.com.ausroads.routing.engine.valhalla

import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.RouteRequest
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.valhalla.api.models.RouteResponse
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.ValhallaResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Actual engine dispatcher/parser with a controlled native response, not JNI/graph proof. */
class RouteDistanceUnitsTest {
    @Test
    fun `kilometer trip and maneuver lengths become domain meters`() = runBlocking {
        val result = compute("kilometers", 1.25, 0.125)
        assertThat(result.distanceMeters).isEqualTo(1250)
        assertThat(result.maneuvers.single().lengthMeters).isEqualTo(125)
        assertThat(result.durationSeconds).isEqualTo(90)
        assertThat(result.maneuvers.single().durationSeconds).isEqualTo(10)
        assertThat(result.geometry).hasSize(2)
    }

    @Test
    fun `subkilometer routes do not truncate to zero meters`() = runBlocking {
        val result = compute("kilometers", 0.25, 0.05)
        assertThat(result.distanceMeters).isEqualTo(250)
        assertThat(result.maneuvers.single().lengthMeters).isEqualTo(50)
    }

    @Test
    fun `reported miles are normalized before publishing meter fields`() = runBlocking {
        val result = compute("miles", 2.0, 0.5)
        assertThat(result.distanceMeters).isEqualTo(3218)
        assertThat(result.maneuvers.single().lengthMeters).isEqualTo(804)
    }

    @Test
    fun `zero length remains a real zero rather than invented progress`() = runBlocking {
        val result = compute("kilometers", 0.0, 0.0)
        assertThat(result.distanceMeters).isEqualTo(0)
        assertThat(result.maneuvers.single().lengthMeters).isEqualTo(0)
    }

    private suspend fun compute(units: String, tripLength: Double, maneuverLength: Double) = run {
        val response = checkNotNull(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(RouteResponse::class.java).fromJson(responseJson(units, tripLength, maneuverLength)))
        val native = mockk<Valhalla>()
        every { native.route(any()) } returns ValhallaResponse.Json(response)
        val engine = ValhallaRoutingEngine { native }
        engine.initialize("controlled-response-only.tar")
        engine.computeRoute(RouteRequest(
            origin = GeoPoint(longitude = 0.0, latitude = 0.0),
            destination = GeoPoint(longitude = 0.1, latitude = 0.1),
        ))
    }

    private fun responseJson(units: String, tripLength: Double, maneuverLength: Double): String {
        val summary = """{"time":90.0,"length":$tripLength,
            "min_lat":0.0,"max_lat":0.1,"min_lon":0.0,"max_lon":0.1}"""
        return """{"trip":{
            "status":0,"status_message":"controlled response","units":"$units",
            "language":"en-US","locations":[],"summary":$summary,
            "legs":[{"summary":$summary,"shape":"??_ibE_ibE","maneuvers":[{
                "type":1,"instruction":"Controlled instruction","time":10.0,
                "length":$maneuverLength,"begin_shape_index":0,"end_shape_index":1,
                "travel_mode":"drive","travel_type":"car"
            }]}]
        }}"""
    }
}
