/*
 * Map screen (v0.1.1).
 *
 * Renders a real MapLibre MapView for the bundled Adelaide test pack at
 * app/src/main/assets/maptile/ when it is present; otherwise falls back to the
 * v0.1 "no map pack installed" placeholder so the screen still composes cleanly
 * in side-loaded builds that ship without the pack.
 *
 * Lifecycle is bound to the host Activity via a DisposableEffect that forwards
 * every Lifecycle.Event to the MapView, in the order recommended by the
 * MapLibre quickstart (onStart → onResume → onPause → onStop → onDestroy).
 * The MapView is hoisted into a `remember` slot so it is not recreated across
 * recompositions.
 *
 * See docs/adr/0007-maplibre-android-integration.md for the integration plan.
 */
@file:Suppress("LargeClass", "TooManyFunctions")

package au.com.ausroads.ui.map

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.PointF
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import au.com.ausroads.R
import au.com.ausroads.core.geo.MeasureGeometry
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.data.pins.Pin
import au.com.ausroads.io.isExpectedSqliteDatabase
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.SearchPresentation
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.installDirectoryName
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.ui.pins.PIN_COLOR_CHOICES
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

private const val STYLE_ASSET = "maptile/style.json"
private const val MBTILES_ASSET = "maptile/adelaide-test-tiles.mbtiles"
private const val MBTILES_HASH_ASSET = "$MBTILES_ASSET.sha256"
private const val MBTILES_FILENAME = "adelaide-test-tiles.mbtiles"
private const val BUNDLED_PACK_IDENTITY = "bundled"

private val AdelaideCbd = LatLng(-34.92, 138.62)
// The bundled vector data first contains POIs at z14, so the demo opens at a
// useful neighbourhood scale instead of a city-wide view with no visible shops.
private const val ADELAIDE_ZOOM = 14.0
private const val MAP_KEYBOARD_PAN_PX = 120f
private const val MAP_KEYBOARD_ANIMATION_MS = 180
// Allow zooming in past the tile maxzoom (overzoom) so POI labels (style minzoom
// 14) are tappable across a usable street-level range, not just a 14.0-14.5 sliver.
private const val MAX_ZOOM = 16.0
private const val MIN_ZOOM = 3.0
private const val MIN_ROUTE_ORIGIN_DISTANCE_METERS = 5.0

/**
 * Copies the bundled MBTiles from assets to filesDir if not already present,
 * then returns the style JSON with the correct `mbtiles://` absolute path.
 *
 * MapLibre Native's `mbtiles://` protocol requires a real filesystem path
 * (it uses `stat()` internally). Android assets are inside the APK and
 * don't have real paths, so we must copy to filesDir first.
 */
private fun ensureMbtilesAndStyle(context: Context, installedPack: InstalledPack?): String {
    val mbtilesPath = resolveMbtilesPath(context, installedPack)
    android.util.Log.i("MapScreen", "Rendering tiles from $mbtilesPath")
    val styleJson = context.assets.open(STYLE_ASSET).bufferedReader().readText()
    return styleJson.replace(
        "asset://maptile/adelaide-test-tiles.mbtiles",
        "mbtiles://$mbtilesPath",
    )
}

/**
 * Resolves the MBTiles file path to render: a pack installed by the in-app
 * downloader takes precedence; otherwise the bundled Adelaide test tiles are
 * copied into filesDir (MapLibre's `mbtiles://` needs a real filesystem path).
 * The style and glyphs are always loaded from assets — only the tile source is
 * repointed.
 */
private fun resolveMbtilesPath(context: Context, installedPack: InstalledPack?): String {
    if (installedPack != null) {
        return checkNotNull(MapPackAvailability.installedPackMbtiles(context, installedPack)) {
            "Activated map pack tiles are missing or invalid"
        }.absolutePath
    }
    val outFile = File(context.filesDir, MBTILES_FILENAME)
    val expectedHash = context.assets.open(MBTILES_HASH_ASSET).bufferedReader().use { reader ->
        reader.readText().trim().substringBefore(' ')
    }
    if (!isExpectedSqliteDatabase(outFile, expectedHash)) {
        val temporary = File(context.filesDir, "$MBTILES_FILENAME.tmp")
        temporary.delete()
        context.assets.open(MBTILES_ASSET).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        check(isExpectedSqliteDatabase(temporary, expectedHash)) {
            "Bundled MBTiles failed its SQLite/checksum contract"
        }
        if (!temporary.renameTo(outFile)) {
            temporary.copyTo(outFile, overwrite = true)
            temporary.delete()
        }
    }
    return outFile.absolutePath
}

/**
 * Style layer IDs we query for tap-to-identify, in priority order. These map
 * to OpenMapTiles source-layers:
 *  - "poi"          → poi
 *  - "road-label"   → transportation_name
 *  - "place-suburb" → place (class == "suburb")
 */
private data class IdentifyLayer(val id: String, val kindRes: Int, val sourceLayer: String)

private val IDENTIFY_LAYERS = listOf(
    IdentifyLayer("poi-shop-dot", R.string.feature_poi, "poi"),
    IdentifyLayer("poi-dot", R.string.feature_poi, "poi"),
    IdentifyLayer("poi", R.string.feature_poi, "poi"),
    IdentifyLayer("road-label", R.string.feature_road, "transportation_name"),
    IdentifyLayer("place-suburb", R.string.feature_place, "place"),
)

@Suppress("LongParameterList")
@Composable
fun MapScreen(
    onOpenSettings: () -> Unit,
    pins: List<Pin> = emptyList(),
    onSavePin: (Double, Double, String, String, (Pin) -> Unit, (Throwable) -> Unit) -> Unit =
        { _, _, _, _, _, _ -> },
    onDeletePin: (Pin, () -> Unit, (Throwable) -> Unit) -> Unit = { _, _, _ -> },
    reverseGeocode: suspend (Double, Double) -> String? = { _, _ -> null },
    showAttribution: Boolean = true,
    searchViewModel: au.com.ausroads.feature.search.SearchViewModel? = null,
    trafficViewModel: au.com.ausroads.feature.traffic.TrafficViewModel? = null,
    trafficEnabled: Boolean = false,
    navigationViewModel: au.com.ausroads.feature.navigation.NavigationViewModel? = null,
    routeHistoryViewModel: RouteHistoryViewModel? = null,
    routeViewModel: RouteViewModel? = null,
    routeAvoidOptions: RouteOptions = RouteOptions(),
    onRouteAvoidOptionsChange: (RouteOptions) -> Unit = {},
    routeOptionsWriteRecovery: RouteOptionsWriteRecovery? = null,
    activePack: InstalledPack? = null,
    packStateInitialized: Boolean = false,
    requestedMapTarget: SearchResult? = null,
    onRequestedMapTargetConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val initialInstalledPack = remember(context) { MapPackAvailability.installedPack(context) }
    val renderInstalledPack = runtimeInstalledPack(
        initialPack = initialInstalledPack,
        activePack = activePack,
        packStateInitialized = packStateInitialized,
    )
    val initialPackIdentity = initialInstalledPack?.runtimeIdentity() ?: BUNDLED_PACK_IDENTITY
    val effectivePackIdentity = mapPackRenderIdentity(
        initialPackIdentity = initialPackIdentity,
        activePackIdentity = activePack?.runtimeIdentity(),
        packStateInitialized = packStateInitialized,
    )
    val hasPack = remember(context, effectivePackIdentity) {
        renderInstalledPack?.let { MapPackAvailability.installedPackMbtiles(context, it) != null }
            ?: MapPackAvailability.hasBundledPack(context)
    }
    val usingBundledFallback = renderInstalledPack == null

    if (!hasPack) {
        NoMapPackPlaceholder(
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    // Recreate MapLibre when the downloader promotes a new current pack. Search
    // and routing already reopened on this event; keying the map keeps all three
    // consumers on the same installed version without requiring an app restart.
    key(effectivePackIdentity) {
        MapScreenContent(
            onOpenSettings = onOpenSettings,
            pins = pins,
            onSavePin = onSavePin,
            onDeletePin = onDeletePin,
            reverseGeocode = reverseGeocode,
            showAttribution = showAttribution,
            searchViewModel = searchViewModel,
            trafficViewModel = trafficViewModel,
            trafficEnabled = trafficEnabled,
            navigationViewModel = navigationViewModel,
            routeHistoryViewModel = routeHistoryViewModel,
            routeViewModel = routeViewModel,
            routeAvoidOptions = routeAvoidOptions,
            onRouteAvoidOptionsChange = onRouteAvoidOptionsChange,
            routeOptionsWriteRecovery = routeOptionsWriteRecovery,
            requestedMapTarget = requestedMapTarget,
            onRequestedMapTargetConsumed = onRequestedMapTargetConsumed,
            installedPack = renderInstalledPack,
            usingBundledFallback = usingBundledFallback,
            modifier = modifier,
        )
    }
}

/**
 * Preserve the synchronous disk identity while MapPackManager reconciles, then
 * trust its settled state. In particular, settled `null` means a stale
 * current.json was removed and the bundled map must replace the placeholder.
 */
internal fun mapPackRenderIdentity(
    initialPackIdentity: String,
    activePackIdentity: String?,
    packStateInitialized: Boolean,
): String = if (packStateInitialized) {
    activePackIdentity ?: BUNDLED_PACK_IDENTITY
} else {
    initialPackIdentity
}

/** Once reconciliation settles, only the successfully activated descriptor may render. */
internal fun runtimeInstalledPack(
    initialPack: InstalledPack?,
    activePack: InstalledPack?,
    packStateInitialized: Boolean,
): InstalledPack? = if (packStateInitialized) activePack else initialPack

private fun InstalledPack.runtimeIdentity(): String =
    "$version:$installedAt:${installDirectoryName()}"

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreenContent(
    onOpenSettings: () -> Unit,
    pins: List<Pin>,
    onSavePin: (Double, Double, String, String, (Pin) -> Unit, (Throwable) -> Unit) -> Unit,
    onDeletePin: (Pin, () -> Unit, (Throwable) -> Unit) -> Unit,
    reverseGeocode: suspend (Double, Double) -> String?,
    showAttribution: Boolean,
    searchViewModel: au.com.ausroads.feature.search.SearchViewModel?,
    trafficViewModel: au.com.ausroads.feature.traffic.TrafficViewModel?,
    trafficEnabled: Boolean,
    navigationViewModel: au.com.ausroads.feature.navigation.NavigationViewModel?,
    routeHistoryViewModel: RouteHistoryViewModel?,
    routeViewModel: RouteViewModel?,
    routeAvoidOptions: RouteOptions,
    onRouteAvoidOptionsChange: (RouteOptions) -> Unit,
    routeOptionsWriteRecovery: RouteOptionsWriteRecovery?,
    requestedMapTarget: SearchResult?,
    onRequestedMapTargetConsumed: () -> Unit,
    installedPack: InstalledPack?,
    usingBundledFallback: Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val recenterRequestOwner = remember { RecenterRequestOwner() }

    // Live traffic is foreground-only: opt-out and leaving the map both stop network work.
    DisposableEffect(lifecycleOwner, trafficEnabled, trafficViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (trafficEnabled) trafficViewModel?.startPolling()
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> trafficViewModel?.stopPolling()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (trafficEnabled && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            trafficViewModel?.startPolling()
        } else {
            trafficViewModel?.stopPolling()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            trafficViewModel?.stopPolling()
        }
    }

    // Traffic overlay on map (v0.2)
    val trafficEvents: List<LiveTrafficEvent> = if (trafficEnabled && trafficViewModel != null) {
        trafficViewModel.events.collectAsState().value
    } else {
        emptyList()
    }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var selectedFeature by remember { mutableStateOf<IdentifiedFeature?>(null) }
    var selectedTrafficEvent by remember { mutableStateOf<LiveTrafficEvent?>(null) }
    var selectedPin by remember { mutableStateOf<Pin?>(null) }
    var selectedSearchResult by remember { mutableStateOf<SearchResult?>(null) }
    var routeOriginBeforeResultFocus by remember { mutableStateOf<GeoPoint?>(null) }
    var routeOriginCapturePending by remember { mutableStateOf(false) }
    var pendingDrop by remember { mutableStateOf<LatLng?>(null) }
    var showRouteHistory by remember { mutableStateOf(false) }
    var mapLoadState by remember { mutableStateOf<MapLoadState>(MapLoadState.Loading) }
    var recenterWhenPermissionGranted by remember { mutableStateOf(false) }
    var didInitialLocationFocus by remember { mutableStateOf(requestedMapTarget != null) }
    var hasUsedLongPress by remember { mutableStateOf(false) }
    var mapHasKeyboardFocus by remember { mutableStateOf(false) }

    // Location permission + live fix (drives the blue dot and the My Location FAB).
    val locationPermission = rememberForegroundLocationPermissionState(
        context = context,
        onDenied = {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.map_location_permission_denied),
                )
            }
        },
    )
    val userLocation = rememberUserLocation(locationPermission.isGranted)

    val recenter: () -> Unit = {
        recenterRequestOwner.replace {
            recenterOnUser(
                map = mapLibreMap,
                context = context,
                cached = userLocation.value,
                scope = scope,
                snackbarHostState = snackbarHostState,
                locatingMessage = context.getString(R.string.map_locating),
                unavailableMessage = context.getString(R.string.map_location_unavailable),
                outsideCoverageMessage = context.getString(R.string.map_location_outside_coverage),
                manageMapPackLabel = context.getString(R.string.map_manage_pack),
                onManageMapPack = onOpenSettings,
                isInsideMapCoverage = { latitude, longitude ->
                    isInsideActiveMapCoverage(
                        usingBundledFallback = usingBundledFallback,
                        latitude = latitude,
                        longitude = longitude,
                    )
                },
            )
        }
    }
    val onMyLocation: () -> Unit = {
        if (locationPermission.isGranted) recenter()
        else {
            if (locationPermission.foregroundNeedsSettings) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.map_location_permission_settings),
                    )
                }
            }
            recenterWhenPermissionGranted = true
            locationPermission.request()
        }
    }
    LaunchedEffect(locationPermission.isGranted, recenterWhenPermissionGranted) {
        if (locationPermission.isGranted && recenterWhenPermissionGranted) {
            recenterWhenPermissionGranted = false
            recenter()
        }
    }

    // Keep the click listener reading the *current* traffic/pin lists without
    // re-registering listeners on every poll (which previously leaked + duplicated).
    val currentTrafficEvents = rememberUpdatedState(trafficEvents)
    val currentPins = rememberUpdatedState(pins)
    val currentSelectedSearchResult = rememberUpdatedState(selectedSearchResult)
    val currentHapticFeedback = rememberUpdatedState(hapticFeedback)

    val routeHistoryRoutes = routeHistoryViewModel?.recentRoutes?.collectAsState()?.value
    val routeHistoryLoading = routeHistoryViewModel?.isLoading?.collectAsState()?.value ?: false
    val routeHistoryLoadError = routeHistoryViewModel?.loadError?.collectAsState()?.value ?: false
    val routeHistorySaveError = routeHistoryViewModel?.saveError?.collectAsState()?.value ?: false

    // Route computation state
    val routeState = routeViewModel?.routeState?.collectAsState()?.value
    val navigationSessionRoute = routeViewModel
        ?.navigationSessionRoute
        ?.collectAsState()
        ?.value
    val routeSurfaces = routeSurfaceState(routeState, navigationSessionRoute)
    val isRouting = routeState is RouteUiState.Loading

    // Surface routing failures instead of dropping Loading/Error on the floor.
    LaunchedEffect(routeState) {
        if (routeState is RouteUiState.Error) {
            val actionLabel = context.getString(
                when (routeState.recovery) {
                    RouteRecovery.RETRY -> R.string.action_retry
                    RouteRecovery.MANAGE_MAP_PACK -> R.string.map_manage_pack
                },
            )
            if (
                snackbarHostState.showSnackbar(routeState.message, actionLabel) ==
                SnackbarResult.ActionPerformed
            ) {
                when (routeState.recovery) {
                    RouteRecovery.RETRY -> routeViewModel?.retry()
                    RouteRecovery.MANAGE_MAP_PACK -> onOpenSettings()
                }
            }
        }
    }

    LaunchedEffect(routeHistorySaveError) {
        val historyViewModel = routeHistoryViewModel ?: return@LaunchedEffect
        if (routeHistorySaveError) {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.route_history_save_failed),
                actionLabel = context.getString(R.string.action_retry),
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) {
                historyViewModel.retrySave()
            } else {
                historyViewModel.dismissSaveError()
            }
        }
    }

    // Start directions to a coordinate. A focused search/Nearby result has already
    // moved the camera onto the destination, so preserve the camera position from
    // before that animation as the no-GPS fallback.
    val startDirectionsTo: (Double, Double) -> Unit = startDirections@{ lat, lon ->
        val vm = routeViewModel ?: return@startDirections
        val destination = GeoPoint(longitude = lon, latitude = lat)
        val liveLocation = userLocation.value?.let { fix ->
            GeoPoint(longitude = fix.longitude, latitude = fix.latitude)
        }
        val currentCamera = mapLibreMap?.cameraPosition?.target?.let { target ->
            GeoPoint(longitude = target.longitude, latitude = target.latitude)
        }
        val origin = chooseRouteOrigin(
            liveLocation = liveLocation,
            currentCamera = currentCamera,
            preFocusCamera = routeOriginBeforeResultFocus,
            destination = destination,
            cameraFocusedOnDestination = selectedSearchResult?.matches(destination) == true,
        )
        if (origin == null) {
            scope.launch {
                if (
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.map_route_origin_unavailable),
                        actionLabel = context.getString(R.string.map_my_location),
                    ) == SnackbarResult.ActionPerformed
                ) {
                    onMyLocation()
                }
            }
            return@startDirections
        }
        vm.setOrigin(origin)
        vm.setDestination(destination)
    }

    val focusMapResult: (SearchResult) -> Unit = { result ->
        routeOriginBeforeResultFocus = mapLibreMap?.cameraPosition?.target?.let { target ->
            GeoPoint(longitude = target.longitude, latitude = target.latitude)
        }
        routeOriginCapturePending = routeOriginBeforeResultFocus == null
        didInitialLocationFocus = true
        selectedSearchResult = result
    }

    // A search can complete while MapLibre is still loading. Keeping the
    // camera crossing in an effect makes that selection replay as soon as the
    // style/map becomes ready instead of silently losing the focus request.
    LaunchedEffect(mapLibreMap, selectedSearchResult) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val result = selectedSearchResult ?: return@LaunchedEffect
        if (routeOriginCapturePending) {
            map.cameraPosition.target?.let { target ->
                routeOriginBeforeResultFocus = GeoPoint(
                    longitude = target.longitude,
                    latitude = target.latitude,
                )
                routeOriginCapturePending = false
            }
        }
        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                LatLng(result.latitude, result.longitude),
                mapZoomForSearchResult(result),
            ),
            700,
        )
    }

    LaunchedEffect(mapLibreMap, requestedMapTarget) {
        val target = requestedMapTarget ?: return@LaunchedEffect
        if (mapLibreMap == null) return@LaunchedEffect
        focusMapResult(target)
        onRequestedMapTargetConsumed()
    }

    // If permission was granted on an earlier run, make the first fresh fix
    // visible automatically. Otherwise the blue dot can exist kilometres off
    // the initial Adelaide camera and look "missing" until the FAB is pressed.
    LaunchedEffect(mapLibreMap, userLocation.value, didInitialLocationFocus) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val fix = userLocation.value ?: return@LaunchedEffect
        if (!didInitialLocationFocus && isInsideActiveMapCoverage(
                usingBundledFallback,
                fix.latitude,
                fix.longitude,
            )
        ) {
            didInitialLocationFocus = true
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                    LatLng(fix.latitude, fix.longitude),
                    MY_LOCATION_INITIAL_ZOOM,
                ),
                700,
            )
        }
    }

    // Keep the native view stable for the entire composition. Creating it in
    // AndroidView.factory and then publishing it through mutable state caused
    // an immediate AndroidView detach/reattach on first composition. MapLibre's
    // render surface could be destroyed during that crossing before
    // getMapAsync was delivered, leaving the loading overlay up forever.
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            // Compose overlays and MapLibre stay in one composited hierarchy.
            .textureMode(true)
        MapView(context, options).also { mv ->
            mv.contentDescription = context.getString(R.string.map_canvas_description)
            mv.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            mv.isFocusable = true
            mv.isFocusableInTouchMode = true
            mv.setOnFocusChangeListener { _, hasFocus ->
                mapHasKeyboardFocus = hasFocus
            }
            mv.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val map = mapLibreMap ?: return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> keyboardPanUpdate(map, mv, 0f, -MAP_KEYBOARD_PAN_PX)
                    KeyEvent.KEYCODE_DPAD_DOWN -> keyboardPanUpdate(map, mv, 0f, MAP_KEYBOARD_PAN_PX)
                    KeyEvent.KEYCODE_DPAD_LEFT -> keyboardPanUpdate(map, mv, -MAP_KEYBOARD_PAN_PX, 0f)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> keyboardPanUpdate(map, mv, MAP_KEYBOARD_PAN_PX, 0f)
                    KeyEvent.KEYCODE_PLUS,
                    KeyEvent.KEYCODE_EQUALS,
                    KeyEvent.KEYCODE_NUMPAD_ADD,
                    -> CameraUpdateFactory.zoomIn()
                    KeyEvent.KEYCODE_MINUS,
                    KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
                    -> CameraUpdateFactory.zoomOut()
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_SPACE,
                    -> {
                        map.cameraPosition.target?.let { center ->
                            hasUsedLongPress = true
                            pendingDrop = LatLng(center.latitude, center.longitude)
                        }
                        return@setOnKeyListener true
                    }
                    else -> return@setOnKeyListener false
                }.also { update ->
                    map.animateCamera(update, MAP_KEYBOARD_ANIMATION_MS)
                }
                true
            }
            android.util.Log.i("MapScreen", "MapView created with TextureView renderer")
            mv.onCreate(null)
            mv.addOnDidFailLoadingMapListener { error ->
                android.util.Log.e("MapScreen", "Map load failed: $error")
                mapLoadState = MapLoadState.Failed
            }
            mv.getMapAsync { map ->
                android.util.Log.i("MapScreen", "MapLibre map ready; preparing offline style")
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        val styleJson = withContext(Dispatchers.IO) {
                            ensureMbtilesAndStyle(context, installedPack)
                        }
                        map.setStyle(Style.Builder().fromJson(styleJson)) {
                            configureMap(map)
                            // Publish only after style readiness: every gesture
                            // and overlay downstream depends on this seam.
                            mapLibreMap = map
                            // Style.OnStyleLoaded is MapLibre's deterministic
                            // usability seam. Waiting for fully=true can block
                            // forever on one missing glyph/sprite/tile even while
                            // the rendered map is already interactive.
                            mapLoadState = MapLoadState.Ready
                            android.util.Log.i("MapScreen", "Map style ready; map interactive")
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        android.util.Log.e("MapScreen", "Preparing offline map failed", error)
                        mapLoadState = MapLoadState.Failed
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag("map-canvas")
                .semantics {
                    contentDescription = context.getString(R.string.map_canvas_description)
                },
            factory = { mapView },
            update = { /* lifecycle is forwarded via DisposableEffect below */ },
        )
        if (mapHasKeyboardFocus) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary)
                    .zIndex(20f),
            )
        }

        // Saved pins + live user-location layers (manage MapLibre layers, no UI)
        PinsMapOverlay(pins = pins, mapLibreMap = mapLibreMap)
        SearchResultMapOverlay(result = selectedSearchResult, mapLibreMap = mapLibreMap)
        UserLocationOverlay(
            latitude = userLocation.value?.latitude,
            longitude = userLocation.value?.longitude,
            accuracyMeters = userLocation.value
                ?.takeIf { it.hasAccuracy() }
                ?.accuracy,
            isPreciseLocationGranted = locationPermission.isPreciseGranted,
            mapLibreMap = mapLibreMap,
        )

        if (showAttribution) {
            OsmAttributionOverlay(modifier = Modifier.align(Alignment.BottomStart))
        }

        // Coverage banner: when rendering the bundled Adelaide demo tiles (no full
        // pack downloaded), make the limited extent explicit instead of silent.
        // Sits just below the search bar so it doesn't collide with it.
        if (usingBundledFallback) {
            DemoCoverageBanner(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp),
            )
        }

        // My Location requests precise + approximate together, accepts either,
        // and is present in both network and no-network builds.
        if (locationPermission.isDeclared) {
            MyLocationFab(
                onClick = onMyLocation,
                isLocated = userLocation.value != null,
                isLocating = locationPermission.isGranted && userLocation.value == null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 72.dp, end = 16.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 136.dp),
        )

        // Search overlay (v0.1.2)
        if (searchViewModel != null) {
            au.com.ausroads.feature.search.SearchOverlay(
                viewModel = searchViewModel,
                onResultSelected = focusMapResult,
                onManageMapPack = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f),
            )
        }

        if (mapLoadState != MapLoadState.Ready) {
            MapLoadOverlay(
                failed = mapLoadState == MapLoadState.Failed,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (selectedSearchResult != null) {
            val result = selectedSearchResult ?: return@Box
            SelectedResultBanner(
                result = result,
                onOpenDetails = {
                    selectedFeature = result.toIdentifiedFeature(
                        SearchPresentation.kindLabel(result.kind),
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp),
            )
        } else if (!hasUsedLongPress) {
            HoldToPinHint(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 88.dp),
            )
        }

        // Traffic status pill (v0.2)
        if (trafficEnabled && trafficViewModel != null) {
            au.com.ausroads.feature.traffic.TrafficStatusPill(
                viewModel = trafficViewModel,
                onOpenTrafficSettings = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 136.dp, end = 16.dp)
                    .zIndex(9f),
            )
        }

        // Traffic events rendered on map
        if (trafficEvents.isNotEmpty()) {
            TrafficMapOverlay(events = trafficEvents, mapLibreMap = mapLibreMap)
        }

        // Keep the overlay owner composed so clearing the route explicitly removes
        // its native source/layer from the current style generation.
        RouteMapOverlay(routeResult = routeSurfaces.mapRoute, mapLibreMap = mapLibreMap)

        // Navigation overlay (v0.7)
        if (navigationViewModel != null) {
            au.com.ausroads.feature.navigation.NavigationOverlay(
                viewModel = navigationViewModel,
                onStopNavigation = {
                    // Save the exact route that started this navigation session.
                    val route = routeViewModel?.navigationSessionRoute?.value
                    if (route != null && routeHistoryViewModel != null && route.geometry.isNotEmpty()) {
                        val origin = route.geometry.first()
                        val dest = route.geometry.last()
                        routeHistoryViewModel.saveRoute(
                            originLat = origin.latitude,
                            originLon = origin.longitude,
                            destLat = dest.latitude,
                            destLon = dest.longitude,
                            distanceMeters = route.distanceMeters,
                            durationSeconds = route.durationSeconds,
                        )
                    }
                    routeViewModel?.endNavigationSession()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Route result sheet
        if (routeViewModel != null) {
            val route = routeSurfaces.sheetRoute
            if (route != null) {
                RouteSheet(
                    result = route,
                    onDismiss = { routeViewModel.clearRoute() },
                    onNavigationStarted = { routeViewModel.beginNavigationSession(route) },
                    navigationViewModel = navigationViewModel,
                    preciseLocationGranted = locationPermission.isPreciseGranted,
                    preciseLocationNeedsSettings = locationPermission.preciseNeedsSettings,
                    onRequestPreciseLocation = locationPermission.request,
                    avoidOptions = routeAvoidOptions,
                    onAvoidOptionsChange = onRouteAvoidOptionsChange,
                    optionsWriteRecovery = routeOptionsWriteRecovery,
                )
            }
        }

        // Route history button
        if (routeHistoryViewModel != null) {
            OutlinedButton(
                onClick = { showRouteHistory = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 128.dp, start = 16.dp),
            ) {
                Text(stringResource(R.string.map_history_button))
            }
        }

        // Route history sheet
        if (showRouteHistory && routeHistoryViewModel != null && routeHistoryRoutes != null) {
            RouteHistorySheet(
                state = RouteHistorySheetState(
                    routes = routeHistoryRoutes,
                    isLoading = routeHistoryLoading,
                    hasLoadError = routeHistoryLoadError,
                ),
                actions = RouteHistorySheetActions(
                    onRetryLoad = routeHistoryViewModel::retryLoad,
                    onRouteSelected = { entity ->
                        if (routeViewModel != null) {
                            routeViewModel.clearRoute()
                            routeViewModel.setOrigin(
                                GeoPoint(longitude = entity.originLon, latitude = entity.originLat),
                            )
                            routeViewModel.setDestination(
                                GeoPoint(longitude = entity.destLon, latitude = entity.destLat),
                            )
                        }
                        showRouteHistory = false
                    },
                    onDismiss = { showRouteHistory = false },
                    onDelete = { entity, onDeleted, onError ->
                        routeHistoryViewModel.deleteRoute(
                            route = entity,
                            onDeleted = onDeleted,
                            onError = onError,
                        )
                    },
                ),
            )
        }

        if (selectedFeature != null) {
            val feature = selectedFeature ?: return@Box
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { selectedFeature = null },
                sheetState = sheetState,
            ) {
                FeatureSheetBody(
                    feature = feature,
                    onNavigate = if (routeViewModel != null) { lat, lon ->
                        startDirectionsTo(lat, lon)
                        selectedFeature = null
                    } else null,
                    onSave = { lat, lon ->
                        val name = feature.name
                        onSavePin(
                            lat,
                            lon,
                            name,
                            PIN_SAVE_COLOR,
                            { saved ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.map_pin_saved, saved.name),
                                    )
                                }
                            },
                            { error ->
                                android.util.Log.e("MapScreen", "Saving identified feature failed", error)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.map_pin_save_failed),
                                    )
                                }
                            },
                        )
                        selectedFeature = null
                    },
                )
            }
        }

        // Traffic event detail sheet
        if (selectedTrafficEvent != null) {
            val event = selectedTrafficEvent ?: return@Box
            TrafficEventSheet(
                event = event,
                onDismiss = { selectedTrafficEvent = null },
            )
        }

        // Routing-in-progress indicator
        if (isRouting) {
            RoutingIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Long-press "drop a pin here" sheet — saves only on explicit confirm.
        val drop = pendingDrop
        if (drop != null) {
            DropPinSheet(
                latitude = drop.latitude,
                longitude = drop.longitude,
                reverseGeocode = reverseGeocode,
                onDismiss = { pendingDrop = null },
                onSave = { name, color, onSuccess, onFailure ->
                    onSavePin(
                        drop.latitude,
                        drop.longitude,
                        name,
                        color,
                        { saved ->
                            pendingDrop = null
                            onSuccess()
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.map_pin_saved, saved.name),
                                    actionLabel = context.getString(R.string.action_undo),
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    onDeletePin(
                                        saved,
                                        {},
                                        { error ->
                                            android.util.Log.e(
                                                "MapScreen",
                                                "Undo saved-pin creation failed",
                                                error,
                                            )
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(
                                                        R.string.pin_delete_failed,
                                                        saved.name,
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        },
                        { error ->
                            android.util.Log.e("MapScreen", "Dropped-pin save failed", error)
                            onFailure(context.getString(R.string.map_pin_save_failed))
                        },
                    )
                },
                onDirections = {
                    startDirectionsTo(drop.latitude, drop.longitude)
                    pendingDrop = null
                },
            )
        }

        // Saved-pin detail sheet (tapped a pin marker)
        val pin = selectedPin
        if (pin != null) {
            PinDetailSheet(
                pin = pin,
                onDismiss = { selectedPin = null },
                onDirections = {
                    startDirectionsTo(pin.lat, pin.lon)
                    selectedPin = null
                },
                onDelete = { onDeleted, onError ->
                    onDeletePin(
                        pin,
                        {
                            onDeleted()
                            selectedPin = null
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.map_pin_deleted, pin.name),
                                )
                            }
                        },
                        onError,
                    )
                },
            )
        }
    }

    // Register map gesture listeners ONCE per map instance. Reading the live
    // pin/traffic lists via rememberUpdatedState avoids re-registering (and thus
    // stacking + leaking) listeners every time those lists change.
    LaunchedEffect(mapLibreMap) {
        val m = mapLibreMap ?: return@LaunchedEffect
        m.addOnMapClickListener { latLng ->
            val screenPoint = m.projection.toScreenLocation(latLng)
            // 1) Saved pins take priority. Use asString (not toString, which keeps
            // the surrounding JSON quotes and breaks the Long parse).
            val pinHits = m.queryRenderedFeatures(screenPoint, PINS_POINTS_LAYER)
            val pinId = pinHits.firstOrNull()?.properties()?.get("id")
                ?.takeIf { !it.isJsonNull }?.asString?.toLongOrNull()
            if (pinId != null) {
                selectedPin = currentPins.value.firstOrNull { it.id == pinId }
                return@addOnMapClickListener true
            }
            // 2) The selected search/Nearby marker sits above the base POI style,
            // so query its own layer and open the same Save/Directions sheet.
            val selectedResult = currentSelectedSearchResult.value
            val selectedResultHits = m.queryRenderedFeatures(
                screenPoint,
                SELECTED_SEARCH_RESULT_POINT_LAYER_ID,
                SELECTED_SEARCH_RESULT_HALO_LAYER_ID,
            )
            if (selectedResult != null && selectedResultHits.isNotEmpty()) {
                selectedFeature = selectedResult.toIdentifiedFeature(
                    context.getString(selectedResult.kindLabelResource()),
                )
                return@addOnMapClickListener true
            }
            // 3) Traffic features (asString, not toString — same JSON-quote pitfall)
            val trafficHits = m.queryRenderedFeatures(screenPoint, "traffic-points")
            val hitId = trafficHits.firstOrNull()?.properties()?.get("id")
                ?.takeIf { !it.isJsonNull }?.asString
            if (hitId != null) {
                selectedTrafficEvent = currentTrafficEvents.value.firstOrNull { it.primaryKey == hitId }
                return@addOnMapClickListener true
            }
            // 4) Labelled map features (road / suburb / POI)
            val hit = identifyFeature(context, m, latLng)
            if (hit != null) selectedFeature = hit
            false
        }
        m.addOnMapLongClickListener { latLng ->
            android.util.Log.i("MapScreen", "Map long-press accepted; opening pin editor")
            hasUsedLongPress = true
            currentHapticFeedback.value.performHapticFeedback(HapticFeedbackType.LongPress)
            pendingDrop = latLng
            true
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val mv = mapView
        var started = false
        var resumed = false

        fun start() {
            if (!started) {
                android.util.Log.i("MapScreen", "Forwarding MapView.onStart")
                mv.onStart()
                started = true
            }
        }
        fun resume() {
            start()
            if (!resumed) {
                android.util.Log.i("MapScreen", "Forwarding MapView.onResume")
                mv.onResume()
                resumed = true
            }
        }
        fun pause() {
            if (resumed) {
                android.util.Log.i("MapScreen", "Forwarding MapView.onPause")
                mv.onPause()
                resumed = false
            }
        }
        fun stop() {
            recenterRequestOwner.cancel()
            pause()
            if (started) {
                android.util.Log.i("MapScreen", "Forwarding MapView.onStop")
                mv.onStop()
                started = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            recenterRequestOwner.cancel()
            stop()
            android.util.Log.i("MapScreen", "Forwarding MapView.onDestroy")
            mv.onDestroy()
            mapLibreMap = null
        }
    }

    // Forward memory pressure to the MapView so it can release GL/tile caches,
    // as the MapLibre quickstart requires. onLowMemory was never wired before.
    DisposableEffect(mapView) {
        val mv = mapView
        @Suppress("DEPRECATION") // memory-pressure constants/onLowMemory; alternatives are also deprecated
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mv.onLowMemory()
            }
            override fun onLowMemory() { mv.onLowMemory() }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
        }
        context.applicationContext.registerComponentCallbacks(callbacks)
        onDispose { context.applicationContext.unregisterComponentCallbacks(callbacks) }
    }
}

private fun configureMap(map: MapLibreMap) {
    val ui = map.uiSettings
    ui.isCompassEnabled = false
    ui.isLogoEnabled = false
    ui.isAttributionEnabled = false

    map.setMaxZoomPreference(MAX_ZOOM)
    map.setMinZoomPreference(MIN_ZOOM)

    map.cameraPosition = CameraPosition.Builder()
        .target(AdelaideCbd)
        .zoom(ADELAIDE_ZOOM)
        .bearing(0.0)
        .tilt(0.0)
        .build()
}

private fun keyboardPanUpdate(
    map: MapLibreMap,
    mapView: MapView,
    xOffsetPx: Float,
    yOffsetPx: Float,
) = CameraUpdateFactory.newLatLng(
    map.projection.fromScreenLocation(
        PointF(
            mapView.width / 2f + xOffsetPx,
            mapView.height / 2f + yOffsetPx,
        ),
    ),
)

internal fun isInsideActiveMapCoverage(
    usingBundledFallback: Boolean,
    latitude: Double,
    longitude: Double,
): Boolean {
    val bounds = if (usingBundledFallback) {
        BUNDLED_COVERAGE
    } else {
        SOUTH_AUSTRALIA_COVERAGE
    }
    return longitude in bounds.west..bounds.east && latitude in bounds.south..bounds.north
}

private data class MapCoverage(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

private val BUNDLED_COVERAGE = MapCoverage(138.4, -35.2, 139.0, -34.6)
private val SOUTH_AUSTRALIA_COVERAGE = MapCoverage(129.0, -38.0, 141.0, -26.0)
private const val MY_LOCATION_INITIAL_ZOOM = 15.0

private fun identifyFeature(context: android.content.Context, map: MapLibreMap, latLng: LatLng): IdentifiedFeature? {
    val screenPoint = map.projection.toScreenLocation(latLng)
    return IDENTIFY_LAYERS.firstNotNullOfOrNull { layer ->
        val hits = map.queryRenderedFeatures(screenPoint, layer.id)
        hits.firstNotNullOfOrNull { hit ->
            val props = hit.properties() ?: return@firstNotNullOfOrNull null
            val name = (props["name:en"] ?: props["name"] ?: props["name:latin"])
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?: return@firstNotNullOfOrNull null
            IdentifiedFeature(
                name = name,
                kind = context.getString(layer.kindRes),
                sourceLayer = layer.sourceLayer,
                featureClass = props["class"]?.takeIf { !it.isJsonNull }?.asString,
                latitude = latLng.latitude,
                longitude = latLng.longitude,
            )
        }
    }
}

internal data class IdentifiedFeature(
    val name: String,
    val kind: String,
    val sourceLayer: String,
    val featureClass: String?,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

internal fun chooseRouteOrigin(
    liveLocation: GeoPoint?,
    currentCamera: GeoPoint?,
    preFocusCamera: GeoPoint?,
    destination: GeoPoint,
    cameraFocusedOnDestination: Boolean,
): GeoPoint? {
    val candidate = liveLocation ?: if (cameraFocusedOnDestination) {
        preFocusCamera ?: currentCamera
    } else {
        currentCamera
    }
    return candidate?.takeIf {
        MeasureGeometry.haversineMeters(it, destination) >= MIN_ROUTE_ORIGIN_DISTANCE_METERS
    }
}

private fun SearchResult.matches(destination: GeoPoint): Boolean =
    latitude == destination.latitude && longitude == destination.longitude

private fun SearchResult.kindLabelResource(): Int = when (kind.lowercase()) {
    "poi" -> R.string.feature_poi
    "road" -> R.string.feature_road
    "suburb" -> R.string.feature_place
    else -> R.string.feature_search_result
}

internal fun SearchResult.toIdentifiedFeature(kindLabel: String): IdentifiedFeature =
    IdentifiedFeature(
        name = name,
        kind = kindLabel,
        sourceLayer = when (kind.lowercase()) {
            "poi" -> "poi"
            "road" -> "transportation_name"
            "suburb" -> "place"
            "park" -> "park"
            "water" -> "water"
            else -> "search"
        },
        featureClass = className,
        latitude = latitude,
        longitude = longitude,
    )

@Composable
private fun FeatureSheetBody(
    feature: IdentifiedFeature,
    onNavigate: ((Double, Double) -> Unit)? = null,
    onSave: ((Double, Double) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = feature.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = feature.kind,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SearchPresentation.classLabel(feature.featureClass)?.let { category ->
            Text(
                text = category,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onSave != null && feature.latitude != 0.0) {
                OutlinedButton(onClick = { onSave(feature.latitude, feature.longitude) }) {
                    Icon(
                        Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(stringResource(R.string.action_save_pin))
                }
            }
            if (onNavigate != null && feature.latitude != 0.0) {
                Button(onClick = { onNavigate(feature.latitude, feature.longitude) }) {
                    Text(stringResource(R.string.route_directions))
                }
            }
        }
    }
}

/**
 * Static coverage banner shown when the map is rendering the bundled Adelaide
 * demo tiles (no full SA pack installed). Lightweight translucent chip, matching
 * the attribution overlay's visual language so the top of the map stays calm.
 */
@Composable
private fun DemoCoverageBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xE6263238))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.map_demo_pack_banner),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SelectedResultBanner(
    result: SearchResult,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val category = SearchPresentation.classLabel(result.className)
        ?: SearchPresentation.kindLabel(result.kind)
    Surface(
        modifier = modifier.clickable(
            onClickLabel = stringResource(R.string.map_open_place_details, result.name),
            onClick = onOpenDetails,
        ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Text(
            text = "${result.name} · $category",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OsmAttributionOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCCFFFFFF))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.map_osm_attribution),
            color = Color(0xFF1A3A52),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun NoMapPackPlaceholder(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.map_no_pack),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.map_no_pack_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
        )
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(stringResource(R.string.settings_redownload))
        }
    }
}

@Composable
private fun MyLocationFab(
    onClick: () -> Unit,
    isLocated: Boolean,
    isLocating: Boolean,
    modifier: Modifier = Modifier,
) {
    val myLocationDescription = stringResource(R.string.map_my_location)
    val locatingDescription = stringResource(R.string.map_locating)
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
    ) {
        if (isLocating) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = locatingDescription },
                strokeWidth = 2.dp,
                color = UserAccentBlue,
            )
        } else {
            Icon(
                imageVector = if (isLocated) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                contentDescription = myLocationDescription,
                // The single "you" accent only when a live fix is shown.
                tint = if (isLocated) UserAccentBlue else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private val UserAccentBlue = Color(0xFF1E88E5)

/**
 * Recenters the camera on the user and the live-location stream for the blue dot
 * are flavor-specific: the network build uses fused location while the no-network
 * build uses Android's on-device LocationManager and links no Google Play Services.
 */

// Default colour for a newly-saved place (one dedicated "saved" green, kept
// distinct from the traffic-severity hues and the "you" blue).
const val PIN_SAVE_COLOR = "#1B5E20"

private val MicroLabelFont = androidx.compose.ui.text.font.FontFamily.Monospace

/**
 * Long-press "drop a pin here" sheet. Reverse-geocodes the point to a suggested
 * name (offline), lets the user adjust name + colour, and persists only on Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList", "TooGenericExceptionCaught")
@Composable
private fun DropPinSheet(
    latitude: Double,
    longitude: Double,
    reverseGeocode: suspend (Double, Double) -> String?,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        color: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    onDirections: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(PIN_SAVE_COLOR) }
    var place by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var userEditedName by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val hasUnsavedChanges = userEditedName || selectedColor != PIN_SAVE_COLOR
    val currentIsSaving by rememberUpdatedState(isSaving)
    val currentHasUnsavedChanges by rememberUpdatedState(hasUnsavedChanges)
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { target ->
            when {
                target != SheetValue.Hidden -> true
                currentIsSaving -> false
                currentHasUnsavedChanges -> {
                    showDiscardConfirmation = true
                    false
                }
                else -> true
            }
        },
    )
    val requestDismiss: () -> Unit = {
        if (!isSaving) {
            if (hasUnsavedChanges) showDiscardConfirmation = true else onDismiss()
        }
    }

    LaunchedEffect(showDiscardConfirmation) {
        if (!showDiscardConfirmation) {
            sheetState.show()
        }
    }

    LaunchedEffect(latitude, longitude) {
        val suggestion = try {
            reverseGeocode(latitude, longitude)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("MapScreen", "Pin reverse-geocode failed; using coordinates", error)
            null
        }
        place = suggestion
        if (name.isBlank() && suggestion != null) name = suggestion
        resolved = true
    }

    ModalBottomSheet(onDismissRequest = requestDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.map_drop_pin_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = (place ?: "%.5f, %.5f".format(latitude, longitude)).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MicroLabelFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    userEditedName = true
                },
                label = { Text(stringResource(R.string.pin_name_label)) },
                singleLine = true,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            val selectedColorName = PIN_COLOR_CHOICES
                .first { choice -> choice.hex == selectedColor }
                .nameRes
            Text(
                text = stringResource(
                    R.string.pin_color_selected,
                    stringResource(selectedColorName),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ColorChoiceRow(
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it },
                enabled = !isSaving,
            )
            saveError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDirections, enabled = !isSaving) {
                    Text(stringResource(R.string.route_directions))
                }
                Button(
                    enabled = !isSaving,
                    onClick = {
                        val finalName = name.trim().ifBlank {
                            place ?: "%.4f, %.4f".format(latitude, longitude)
                        }
                        isSaving = true
                        saveError = null
                        onSave(
                            finalName,
                            selectedColor,
                            { isSaving = false },
                            { message ->
                                isSaving = false
                                saveError = message
                            },
                        )
                    },
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (showDiscardConfirmation) {
        DiscardPinChangesDialog(
            onKeepEditing = { showDiscardConfirmation = false },
            onDiscard = onDismiss,
        )
    }
}

/** Saved-pin detail sheet shown when a pin marker is tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinDetailSheet(
    pin: Pin,
    onDismiss: () -> Unit,
    onDirections: () -> Unit,
    onDelete: (onDeleted: () -> Unit, onError: (Throwable) -> Unit) -> Unit,
) {
    var showDeleteConfirmation by remember(pin.id) { mutableStateOf(false) }
    var isDeleting by remember(pin.id) { mutableStateOf(false) }
    var deleteError by remember(pin.id) { mutableStateOf<String?>(null) }
    val currentIsDeleting by rememberUpdatedState(isDeleting)
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { target -> target != SheetValue.Hidden || !currentIsDeleting },
    )
    val deleteFailureMessage = stringResource(R.string.pin_delete_failed, pin.name)
    ModalBottomSheet(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pinColor = remember(pin.color) {
                runCatching { Color(android.graphics.Color.parseColor(pin.color)) }
                    .getOrDefault(Color.Gray)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(pinColor),
                )
                Text(
                    text = pin.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                text = "%.5f, %.5f".format(pin.lat, pin.lon).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MicroLabelFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            deleteError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = !isDeleting,
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = stringResource(R.string.pin_delete),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Button(onClick = onDirections, enabled = !isDeleting) {
                    Text(stringResource(R.string.route_directions))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(stringResource(R.string.pin_delete_confirm_title, pin.name))
            },
            text = { Text(stringResource(R.string.pin_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        isDeleting = true
                        deleteError = null
                        onDelete(
                            { isDeleting = false },
                            { error ->
                                android.util.Log.e("MapScreen", "Deleting saved pin failed", error)
                                isDeleting = false
                                deleteError = deleteFailureMessage
                            },
                        )
                    },
                ) {
                    Text(
                        text = stringResource(R.string.pin_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ColorChoiceRow(
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        PIN_COLOR_CHOICES.forEach { choice ->
            val isSelected = choice.hex == selectedColor
            val colorName = stringResource(choice.nameRes)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(choice.hex)))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onColorSelected(choice.hex) },
                    )
                    .semantics { contentDescription = colorName },
            )
        }
    }
}

@Composable
private fun DiscardPinChangesDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.pin_discard_confirm_title)) },
        text = { Text(stringResource(R.string.pin_discard_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    text = stringResource(R.string.pin_discard),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text(stringResource(R.string.pin_keep_editing))
            }
        },
    )
}

@Composable
private fun RoutingIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.route_computing),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
