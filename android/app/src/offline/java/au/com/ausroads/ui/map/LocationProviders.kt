/*
 * offline-flavor location providers — no-op twins of the withNetwork fused-location
 * providers.
 *
 * The privacy-first `offline` flavor declares no location permission (the manifest
 * strips ACCESS_*_LOCATION) and links no Google Play Services, so there is never a
 * live fix: the blue dot and the "My Location" FAB are hidden upstream
 * (isLocationFeatureDeclared() == false). These stubs exist only so the shared
 * MapScreen in src/main compiles against identical signatures in this flavor.
 */
package au.com.ausroads.ui.map

import android.content.Context
import android.location.Location
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import org.maplibre.android.maps.MapLibreMap

/** No location services in the offline flavor: the fix is always absent. */
@Composable
@Suppress("UnusedParameter")
internal fun rememberUserLocation(hasPermission: Boolean): State<Location?> =
    remember { mutableStateOf<Location?>(null) }

/** No-op: the offline flavor has no location services to recenter on. */
@Suppress("LongParameterList", "UnusedParameter")
internal fun recenterOnUser(
    map: MapLibreMap?,
    context: Context,
    cached: Location?,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    locatingMessage: String,
    unavailableMessage: String,
) = Unit
