package au.com.ausroads.traffic.provider

private const val HTTP_SUCCESS_MIN = 200
private const val HTTP_SUCCESS_MAX = 299
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403

/**
 * Sanitized provider failure. It deliberately carries no request headers, URL query,
 * response body, or credential value so it is safe to propagate across app layers.
 */
sealed class TrafficProviderException(message: String) : Exception(message)

class TrafficProviderAuthenticationException(
    val regionCode: String,
) : TrafficProviderException("Authentication rejected by traffic provider for $regionCode")

class TrafficProviderHttpException(
    val regionCode: String,
    val statusCode: Int,
) : TrafficProviderException("Traffic provider request for $regionCode failed with HTTP $statusCode")

/**
 * Converts upstream HTTP status into a typed, credential-safe provider failure.
 * Conditional 304 responses are handled by providers before calling this function.
 */
fun requireSuccessfulTrafficResponse(
    regionCode: String,
    statusCode: Int,
) {
    when {
        statusCode in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX -> Unit
        statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN ->
            throw TrafficProviderAuthenticationException(regionCode)
        else -> throw TrafficProviderHttpException(regionCode, statusCode)
    }
}
