package au.com.ausroads.feature.search

import au.com.ausroads.core.geo.MeasureGeometry
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.offline.search.SearchResult
import java.util.Locale
import kotlin.math.roundToInt

/** Display only: disambiguate same-named segments without changing matching/order/selection. */
internal fun mapDistanceLabel(result: SearchResult, reference: GeoPoint?): String? {
    if (
        reference == null ||
            !result.latitude.isFinite() ||
            !result.longitude.isFinite() ||
            result.latitude !in -90.0..90.0 ||
            result.longitude !in -180.0..180.0
    )
        return null
    val meters =
        MeasureGeometry.haversineMeters(
            reference,
            GeoPoint(longitude = result.longitude, latitude = result.latitude),
        )
    if (!meters.isFinite()) return null
    return if (meters < 1_000.0) "${meters.roundToInt()} m"
    else String.format(Locale.getDefault(), "%.1f km", meters / 1_000.0)
}
