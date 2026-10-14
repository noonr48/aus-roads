package au.com.ausroads.ui.map

import au.com.ausroads.offline.search.SpeedCameraPoint
import java.util.Locale

/** Same legacy identity for feature production and physical tap lookup. */
internal fun SpeedCameraPoint.mapFeatureId(): String =
    "%.6f,%.6f".format(Locale.US, latitude, longitude)
