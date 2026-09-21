package au.com.ausroads.traffic.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TrafficProviderExceptionTest {

    @Test
    fun `401 is a typed authentication rejection for the provider region`() {
        val failure = assertThrows(TrafficProviderAuthenticationException::class.java) {
            requireSuccessfulTrafficResponse(regionCode = "AU-NSW", statusCode = 401)
        }

        assertThat(failure.regionCode).isEqualTo("AU-NSW")
        assertThat(failure.message).doesNotContain("key")
    }

    @Test
    fun `403 is a typed authentication rejection for the provider region`() {
        val failure = assertThrows(TrafficProviderAuthenticationException::class.java) {
            requireSuccessfulTrafficResponse(regionCode = "AU-VIC", statusCode = 403)
        }

        assertThat(failure.regionCode).isEqualTo("AU-VIC")
    }

    @Test
    fun `500 remains an upstream request failure rather than an authentication rejection`() {
        val failure = assertThrows(TrafficProviderHttpException::class.java) {
            requireSuccessfulTrafficResponse(regionCode = "AU-NSW", statusCode = 500)
        }

        assertThat(failure.regionCode).isEqualTo("AU-NSW")
        assertThat(failure.statusCode).isEqualTo(500)
    }

    @Test
    fun `successful response status does not throw`() {
        requireSuccessfulTrafficResponse(regionCode = "AU-VIC", statusCode = 204)
    }
}
