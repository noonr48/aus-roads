package au.com.ausroads.ui.pins

internal fun isValidLatitude(value: Double?): Boolean =
    value != null && value.isFinite() && value in -90.0..90.0

internal fun isValidLongitude(value: Double?): Boolean =
    value != null && value.isFinite() && value in -180.0..180.0

internal fun areValidPinCoordinates(latitude: Double, longitude: Double): Boolean =
    isValidLatitude(latitude) && isValidLongitude(longitude)
