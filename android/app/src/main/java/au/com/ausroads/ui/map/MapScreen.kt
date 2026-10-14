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

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import au.com.ausroads.R
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.data.pins.Pin
import au.com.ausroads.feature.navigation.NavigationState
import au.com.ausroads.navigation.NavigationSessionBus
import au.com.ausroads.navigation.NavigationSessionService
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.SpeedCameraPoint
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

private val AdelaideCbd = LatLng(-34.92, 138.62)
private const val ADELAIDE_ZOOM = 11.0
// Allow zooming in past the tile maxzoom (overzoom) so POI labels (style minzoom
// 14) are tappable across a usable street-level range, not just a 14.0-14.5 sliver.
private const val MAX_ZOOM = 16.0
private const val MIN_ZOOM = 3.0

/**
 * Style layer IDs we query for tap-to-identify, in priority order. These map to OpenMapTiles
 * source-layers:
 * - "poi" → poi
 * - "road-label" → transportation_name
 * - "place-suburb" → place (class == "suburb")
 */
private data class IdentifyLayer(val id: String, val kindRes: Int, val sourceLayer: String)

private val IDENTIFY_LAYERS =
    listOf(
        IdentifyLayer("poi", R.string.feature_poi, "poi"),
        IdentifyLayer("road-label", R.string.feature_road, "transportation_name"),
        IdentifyLayer("place-suburb", R.string.feature_place, "place"),
    )

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onOpenSettings: () -> Unit,
    pins: List<Pin> = emptyList(),
    onSavePin: (Double, Double, String, String, (Pin) -> Unit) -> Unit = { _, _, _, _, _ -> },
    onDeletePin: (Pin) -> Unit = {},
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
    camerasEnabled: Boolean = true,
    onNavigationActiveChanged: (Boolean) -> Unit = {},
    initialCenter: GeoPoint? = null,
    requestedPlace: SearchResult? = null,
    onPlaceShown: (SearchResult) -> Unit = {},
    onMapAreaChanged: (Double, Double) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Reactive pack state: observing MapPackManager.installed means a pack
    // downloaded in Settings shows up here without an activity recreation.
    // The bundled asset set never changes within a process, so a one-shot
    // check remains correct for that half of the availability decision.
    val packStateViewModel: MapPackStateViewModel = hiltViewModel()
    val installedPack by packStateViewModel.installed.collectAsState()
    val hasBundledPack = remember(context) { MapPackAvailability.hasBundledPack(context) }
    val hasPack = installedPack != null || hasBundledPack
    var usingBundledTiles by remember { mutableStateOf(installedPack == null && hasBundledPack) }

    if (!hasPack) {
        NoMapPackPlaceholder(
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    // Keep content/domain/service ownership mounted while assets/styles are prepared.
    // No second 22-parameter forwarding component or readiness-gated state lifetime.
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Speed-camera layer (v1.1): viewport-driven, pack-backed, toggleable.
    val camerasViewModel: CamerasViewModel = hiltViewModel()
    val camerasState by camerasViewModel.state.collectAsState()
    val currentCamerasEnabled = rememberUpdatedState(camerasEnabled)

    // Poll for traffic updates while the overlay is enabled; stop when toggled
    // off or when this composition leaves, so neither battery nor data are
    // spent polling for an overlay nobody is looking at.
    DisposableEffect(trafficEnabled, trafficViewModel) {
        if (trafficEnabled) {
            trafficViewModel?.startPolling()
        }
        onDispose {
            trafficViewModel?.stopPolling()
        }
    }

    // Traffic overlay on map (v0.2)
    val trafficEvents: List<LiveTrafficEvent> =
        if (trafficEnabled && trafficViewModel != null) {
            trafficViewModel.events.collectAsState().value
        } else {
            emptyList()
        }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var rawMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var overlaysDetached by remember { mutableStateOf(true) }
    var mapConfigured by remember { mutableStateOf(false) }
    var preparingMap by remember { mutableStateOf(true) }
    var mapAssetError by remember { mutableStateOf(false) }
    var mapAssetRetry by remember { mutableStateOf(0) }
    val assetPreparer = remember(context) { MapAssetPreparer(context.applicationContext) }
    val currentInitialCenter = rememberUpdatedState(initialCenter)
    var pendingSearchPlace by remember { mutableStateOf<SearchResult?>(null) }
    var selectedFeature by remember { mutableStateOf<IdentifiedFeature?>(null) }
    var highlightedPlace by remember { mutableStateOf<SearchResult?>(null) }
    val currentAreaCallback = rememberUpdatedState(onMapAreaChanged)
    var selectedTrafficEvent by remember { mutableStateOf<LiveTrafficEvent?>(null) }
    var selectedPin by remember { mutableStateOf<Pin?>(null) }
    var pendingDrop by remember { mutableStateOf<LatLng?>(null) }
    var showRouteHistory by remember { mutableStateOf(false) }

    // Acknowledge the frame actually applied, not a later mutation of mapLibreMap.
    // Reading live state inside SideEffect can certify detachment while this frame
    // still owns the old overlays, then skip their remount on the same native map.
    val frameMap = mapLibreMap
    SideEffect { overlaysDetached = frameMap == null }
    LaunchedEffect(rawMap, installedPack, mapAssetRetry) {
        val map = rawMap ?: return@LaunchedEffect
        val selectedPack = installedPack // freeze the requested adoption identity before IO.
        preparingMap = true
        mapAssetError = false
        if (mapLibreMap != null) {
            overlaysDetached = false
            mapLibreMap = null
        }
        var assetsReady = false
        var preparationStep = "asset-prepare"
        // One recovery boundary covers file, native-style and camera/publication failures;
        // cancellation still propagates, and every ordinary failure names its exact step.
        @Suppress("TooGenericExceptionCaught")
        try {
            val prepared = withContext(Dispatchers.IO) { assetPreparer.prepare(selectedPack) }
            packStateViewModel.recordAssets(true)
            assetsReady = true
            preparationStep = "overlay-frame-ack"
            snapshotFlow { overlaysDetached }.first { it }
            preparationStep = "native-style-load"
            map.awaitPreparedStyle(prepared.json)
            // awaitPreparedStyle returns to its caller's dispatcher. Native camera and
            // projection calls must own Android Main even under a custom effect context.
            withContext(Dispatchers.Main.immediate) {
                preparationStep = "camera-configuration"
                if (!mapConfigured) {
                    configureMap(map, currentInitialCenter.value)
                    mapConfigured = true
                }
                usingBundledTiles = prepared.usingBundled
                preparationStep = "area-camera-publication"
                map.cameraPosition.target?.let {
                    currentAreaCallback.value(it.latitude, it.longitude)
                    if (currentCamerasEnabled.value) {
                        val halfSpan = map.projection.visibleRegion.latLngBounds.latitudeSpan / 2.0 * 111_320.0
                        camerasViewModel.onViewportChanged(it.latitude, it.longitude, halfSpan)
                    }
                }
                mapLibreMap = map
                preparingMap = false
                packStateViewModel.recordStyle(true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Existing fixed-cardinality persistent health remains authoritative; this
            // bounded local witness disambiguates its failed boundary without private payloads.
            android.util.Log.e("MapPreparation", "component=map seam=prepare step=$preparationStep " +
                "assets_ready=$assetsReady class=${error.javaClass.simpleName}")
            if (assetsReady) packStateViewModel.recordStyle(false)
            else packStateViewModel.recordAssets(false)
            mapAssetError = true
            preparingMap = false
        }
    }

    // Location permission + live fix (drives the blue dot and the My Location FAB).
    // The `offline` flavor strips location from the manifest by design, so the whole
    // feature is hidden there rather than offering a dead button.
    val locationAvailable = remember(context) { isLocationFeatureDeclared(context) }
    var hasLocationPermission by remember {
        mutableStateOf(
            locationAvailable &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val userLocation = rememberUserLocation(hasLocationPermission)

    val recenter: () -> Unit = {
        recenterOnUser(
            map = mapLibreMap,
            context = context,
            cached = userLocation.value,
            scope = scope,
            snackbarHostState = snackbarHostState,
            locatingMessage = context.getString(R.string.map_locating),
            unavailableMessage = context.getString(R.string.map_location_unavailable),
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasLocationPermission = granted
            if (granted) recenter()
        }
    val onMyLocation: () -> Unit = {
        if (hasLocationPermission) recenter()
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // Keep the click listener reading the *current* traffic/pin lists without
    // re-registering listeners on every poll (which previously leaked + duplicated).
    val currentTrafficEvents = rememberUpdatedState(trafficEvents)
    val currentPins = rememberUpdatedState(pins)
    val currentCameras = rememberUpdatedState(camerasState.cameras)

    val routeHistoryRoutes = routeHistoryViewModel?.recentRoutes?.collectAsState()?.value

    // Route computation state
    val routeState = routeViewModel?.routeState?.collectAsState()?.value
    val activeRoute = (routeState as? RouteUiState.Active)?.result
    val isRouting = routeState is RouteUiState.Loading

    // Surface routing failures instead of dropping Loading/Error on the floor.
    LaunchedEffect(routeState) {
        if (routeState is RouteUiState.Error) {
            snackbarHostState.showSnackbar(routeState.message)
        }
    }

    // Persist the started route to history at navigation START so process death
    // mid-navigation cannot lose it. Exact-started-route semantics: the record
    // comes from the route the user actually started (RouteViewModel still holds
    // it until Stop, and mid-session recalculations never overwrite it), saved
    // once per navigation session and retained through Stop.
    LaunchedEffect(navigationViewModel, routeViewModel, routeHistoryViewModel) {
        val navVm = navigationViewModel ?: return@LaunchedEffect
        val historyVm = routeHistoryViewModel ?: return@LaunchedEffect
        var savedForSession = false
        navVm.state.collect { navState ->
            when {
                navState is NavigationState.Idle -> savedForSession = false
                navState is NavigationState.Navigating && !savedForSession -> {
                    val result = (routeViewModel?.routeState?.value as? RouteUiState.Active)?.result
                    if (result != null && result.geometry.isNotEmpty()) {
                        savedForSession = true
                        val origin = result.geometry.first()
                        val dest = result.geometry.last()
                        historyVm.saveRoute(
                            originLat = origin.latitude,
                            originLon = origin.longitude,
                            destLat = dest.latitude,
                            destLon = dest.longitude,
                            distanceMeters = result.distanceMeters,
                            durationSeconds = result.durationSeconds,
                        )
                    }
                }
            }
        }
    }

    // Promote/demote the navigation foreground service with the session state
    // (withNetwork keeps GPS alive with the screen off; the offline twin is a
    // no-op). Also report chrome visibility so the app shell can hide the
    // bottom bar while the nav banner owns the bottom edge.
    val navState = navigationViewModel?.state?.collectAsState()?.value
    val navigationActive =
        navState is NavigationState.Navigating || navState is NavigationState.Recalculating
    val searchText = searchViewModel?.query?.collectAsState()?.value.orEmpty()
    val drawCameras = cameraSearchVisibility(camerasEnabled, navigationActive, searchText)
    val currentDrawCameras = rememberUpdatedState(drawCameras)
    LaunchedEffect(drawCameras) {
        if (!drawCameras) camerasViewModel.select(null)
    }
    DisposableEffect(navigationViewModel, navigationActive) {
        if (navigationActive) {
            NavigationSessionService.start(context)
        } else {
            NavigationSessionService.stop(context)
        }
        onDispose { NavigationSessionService.stop(context) }
    }
    // The FGS notification's Stop action routes through the session bus.
    LaunchedEffect(navigationViewModel, routeViewModel) {
        NavigationSessionBus.stopRequests.collect {
            navigationViewModel?.stopNavigation()
            routeViewModel?.clearRoute()
        }
    }
    LaunchedEffect(navigationActive) {
        onNavigationActiveChanged(navigationActive)
    }

    // While navigating, keep the map centred on the live fix (north-up follow).
    // Re-centres only when the fix leaves a ~20 m dead band so a 1 Hz GPS
    // stream cannot fight the user's pan gesture or spam camera animations.
    var lastFollowTarget by remember { mutableStateOf<LatLng?>(null) }
    LaunchedEffect(navigationActive, userLocation.value, mapLibreMap) {
        val fix = userLocation.value ?: return@LaunchedEffect
        if (!navigationActive) {
            lastFollowTarget = null
            return@LaunchedEffect
        }
        val readyMap = mapLibreMap ?: return@LaunchedEffect
        val fixPoint = LatLng(fix.latitude, fix.longitude)
        val last = lastFollowTarget
        val movedFarEnough =
            last == null ||
                run {
                    val latMeters = Math.abs(last.latitude - fixPoint.latitude) * 111_320.0
                    val lonMeters =
                        Math.abs(last.longitude - fixPoint.longitude) *
                            111_320.0 *
                            Math.cos(Math.toRadians(fixPoint.latitude))
                    latMeters + lonMeters > 20.0
                }
        if (movedFarEnough) {
            lastFollowTarget = fixPoint
            withContext(Dispatchers.Main.immediate) {
                readyMap.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLng(fixPoint),
                    900,
                )
            }
        }
    }

    // Start directions to a coordinate, preferring the live GPS fix as the origin
    // and falling back to the current map centre when no fix is available.
    val startDirectionsTo: (Double, Double) -> Unit = startDirections@{ lat, lon ->
        val vm = routeViewModel ?: return@startDirections
        val originLat =
            userLocation.value?.latitude ?: mapLibreMap?.cameraPosition?.target?.latitude
        val originLon =
            userLocation.value?.longitude ?: mapLibreMap?.cameraPosition?.target?.longitude
        if (originLat != null && originLon != null) {
            vm.setOrigin(GeoPoint(longitude = originLon, latitude = originLat))
        }
        vm.setDestination(GeoPoint(longitude = lon, latitude = lat))
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapLibre.getInstance(ctx)
                MapView(ctx).also { mv ->
                    mv.onCreate(null)
                    mapView = mv // getMapAsync may complete inline; establish ownership first.
                    mv.getMapAsync { map ->
                        if (mapView === mv) rawMap = map
                    }
                }
            },
            update = { /* lifecycle is forwarded via DisposableEffect below */ },
        )

        if (preparingMap || mapAssetError) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(if (mapAssetError) R.string.map_load_error else R.string.map_loading))
                    if (mapAssetError) {
                        TextButton(onClick = { mapAssetRetry++ }) { Text(stringResource(R.string.explore_retry)) }
                    } else {
                        CircularProgressIndicator(Modifier.padding(top = 12.dp).size(24.dp))
                    }
                }
            }
        }

        // Saved pins + live user-location layers (manage MapLibre layers, no UI)
        PinsMapOverlay(pins = pins, mapLibreMap = mapLibreMap)
        SelectedPlaceOverlay(place = highlightedPlace, map = mapLibreMap)
        UserLocationOverlay(
            latitude = userLocation.value?.latitude,
            longitude = userLocation.value?.longitude,
            mapLibreMap = mapLibreMap,
        )

        if (showAttribution) {
            OsmAttributionOverlay(modifier = Modifier.align(Alignment.BottomStart))
        }

        // Coverage banner: when rendering the bundled Adelaide demo tiles (no full
        // pack downloaded), make the limited extent explicit instead of silent.
        // Sits just below the search bar so it doesn't collide with it.
        if (usingBundledTiles && searchViewModel == null) {
            DemoCoverageBanner(modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }

        // My Location FAB — recenters on the live fix, requests permission if
        // needed, and gives feedback when no fix is available yet. Hidden on the
        // offline flavor, which has no location permission by design.
        if (locationAvailable && !navigationActive) {
            MyLocationFab(
                onClick = onMyLocation,
                isLocated = userLocation.value != null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 56.dp, end = 16.dp),
            )
        }

        if (!navigationActive) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
            )
        }

        // Search overlay (v0.1.2)
        if (searchViewModel != null && !navigationActive) {
            au.com.ausroads.feature.search.SearchOverlay(
                viewModel = searchViewModel,
                mapReference = initialCenter,
                onResultSelected = { result ->
                    highlightedPlace = result
                    selectedFeature = result.asIdentifiedFeature(context)
                    pendingSearchPlace = result // readiness does not discard an explicit selection.
                    // Selection means inspect a place, not start calculating a route.
                },
                modifier = Modifier.align(Alignment.TopCenter),
                coverageHint =
                    if (usingBundledTiles) context.getString(R.string.map_demo_pack_banner)
                    else null,
                statusSlot = {
                    if (drawCameras && camerasState.queryFailed) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                context.getString(R.string.map_cameras_error),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            androidx.compose.material3.TextButton(onClick = camerasViewModel::retry) {
                                Text(context.getString(R.string.map_cameras_retry))
                            }
                        }
                    }
                    if (trafficEnabled && trafficViewModel != null) {
                        au.com.ausroads.feature.traffic.TrafficStatusPill(
                            viewModel = trafficViewModel,
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                        )
                    }
                },
            )
        }

        // Traffic events rendered on map
        if (trafficEvents.isNotEmpty()) {
            TrafficMapOverlay(events = trafficEvents, mapLibreMap = mapLibreMap)
        }

        // Speed-camera layer (v1.1): pack-driven, offline, toggleable.
        if (drawCameras) {
            CamerasMapOverlay(cameras = camerasState.cameras, mapLibreMap = mapLibreMap)
        }

        // Route line on map
        if (activeRoute != null) {
            RouteMapOverlay(routeResult = activeRoute, mapLibreMap = mapLibreMap)
        }

        // Navigation overlay (v0.7)
        if (navigationViewModel != null) {
            au.com.ausroads.feature.navigation.NavigationOverlay(
                viewModel = navigationViewModel,
                onStopNavigation = {
                    // History was persisted at navigation START; stopping must not
                    // duplicate the record — just tear down the active route.
                    routeViewModel?.clearRoute()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Route result sheet
        if (routeViewModel != null) {
            val route = (routeState as? RouteUiState.Active)?.result
            // Keep the exact started route until End/Stop; a refused Start returns
            // to this same preview only when its honest banner is dismissed.
            if (route != null && (navState == null || navState is NavigationState.Idle)) {
                RouteSheet(
                    result = route,
                    onDismiss = { routeViewModel.clearRoute() },
                    navigationViewModel = navigationViewModel,
                    avoidOptions = routeAvoidOptions,
                    onAvoidOptionsChange = onRouteAvoidOptionsChange,
                )
            }
        }

        // History belongs to the map's lower tool row, never over the search input.
        if (routeHistoryViewModel != null && !navigationActive) {
            Surface(
                modifier =
                    Modifier.align(Alignment.BottomStart).padding(bottom = 48.dp, start = 16.dp),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showRouteHistory = true }) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.map_history_button),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    if (camerasEnabled && camerasState.cameras.isNotEmpty()) {
                        TextButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.PhotoCamera,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                stringResource(R.string.map_cameras_layer),
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // Route history sheet
        if (showRouteHistory && routeHistoryViewModel != null && routeHistoryRoutes != null) {
            RouteHistorySheet(
                routes = routeHistoryRoutes,
                onRouteSelected = { entity ->
                    if (routeViewModel != null) {
                        routeViewModel.clearRoute()
                        routeViewModel.setOrigin(
                            GeoPoint(longitude = entity.originLon, latitude = entity.originLat)
                        )
                        routeViewModel.setDestination(
                            GeoPoint(longitude = entity.destLon, latitude = entity.destLat)
                        )
                    }
                    showRouteHistory = false
                },
                onDismiss = { showRouteHistory = false },
                onDelete = { entity -> routeHistoryViewModel.deleteRoute(entity) },
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
                    onNavigate =
                        if (routeViewModel != null)
                            { lat, lon ->
                                startDirectionsTo(lat, lon)
                                selectedFeature = null
                            }
                        else null,
                    onSave = { lat, lon ->
                        val name = feature.name
                        onSavePin(lat, lon, name, PIN_SAVE_COLOR) { saved ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.map_pin_saved, saved.name)
                                )
                            }
                        }
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

        // Speed-camera detail sheet
        val selectedCamera = camerasState.selected
        if (selectedCamera != null && drawCameras) {
            CameraDetailSheet(
                camera = selectedCamera,
                onDismiss = { camerasViewModel.select(null) },
                onDirections = {
                    startDirectionsTo(selectedCamera.latitude, selectedCamera.longitude)
                    camerasViewModel.select(null)
                },
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
                onSave = { name, color ->
                    onSavePin(drop.latitude, drop.longitude, name, color) { saved ->
                        scope.launch {
                            val result =
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.map_pin_saved, saved.name),
                                    actionLabel = context.getString(R.string.action_undo),
                                    duration = SnackbarDuration.Short,
                                )
                            if (result == SnackbarResult.ActionPerformed) onDeletePin(saved)
                        }
                    }
                    pendingDrop = null
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
                onDelete = {
                    onDeletePin(pin)
                    selectedPin = null
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.map_pin_deleted, pin.name)
                        )
                    }
                },
            )
        }
    }

    LaunchedEffect(pendingSearchPlace, mapLibreMap) {
        val place = pendingSearchPlace ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                    LatLng(place.latitude, place.longitude), 15.0,
                ), 700,
            )
            pendingSearchPlace = null
        }
    }

    // An Explore/Essentials selection waits for the real style before crossing into the map.
    LaunchedEffect(requestedPlace, mapLibreMap) {
        val place = requestedPlace ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            highlightedPlace = place
            selectedFeature = place.asIdentifiedFeature(context)
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                    LatLng(place.latitude, place.longitude),
                    15.0,
                ),
                700,
            )
            onPlaceShown(place)
        }
    }

    // Register map gesture listeners ONCE per map instance. Reading the live
    // pin/traffic lists via rememberUpdatedState avoids re-registering (and thus
    // stacking + leaking) listeners every time those lists change.
    DisposableEffect(mapLibreMap) {
        val map = mapLibreMap ?: return@DisposableEffect onDispose { }
        val click = MapLibreMap.OnMapClickListener { latLng ->
            val point = map.projection.toScreenLocation(latLng)
            // Saved pins → cameras → traffic → labelled features, with live lists.
            val pinHit = map.queryRenderedFeatures(point, PINS_POINTS_LAYER).firstOrNull()
            if (pinHit != null) {
                val pinId = pinHit.properties()?.get("id")?.takeIf { it.isJsonPrimitive }
                    ?.asString?.toLongOrNull()
                selectedPin = pinId?.let { id -> currentPins.value.firstOrNull { it.id == id } }
                // A stale/malformed native pin is still a pin hit, not a different
                // feature's Save/create action. Only the current matching row opens.
                return@OnMapClickListener true
            }
            val cameraId = if (currentDrawCameras.value) {
                map.queryRenderedFeatures(point, "cameras-points")
                    .firstOrNull()?.properties()?.get("id")?.takeIf { !it.isJsonNull }?.asString
            } else null
            if (cameraId != null) {
                camerasViewModel.select(currentCameras.value.firstOrNull { it.mapFeatureId() == cameraId })
                return@OnMapClickListener true
            }
            val trafficId = map.queryRenderedFeatures(point, "traffic-points")
                .firstOrNull()?.properties()?.get("id")?.takeIf { !it.isJsonNull }?.asString
            if (trafficId != null) {
                selectedTrafficEvent = currentTrafficEvents.value.firstOrNull { it.primaryKey == trafficId }
                return@OnMapClickListener true
            }
            identifyFeature(context, map, latLng)?.let { selectedFeature = it }
            false
        }
        val longClick = MapLibreMap.OnMapLongClickListener { latLng ->
            pendingDrop = latLng
            true
        }
        val idle = MapLibreMap.OnCameraIdleListener {
            val target = map.cameraPosition.target ?: return@OnCameraIdleListener
            currentAreaCallback.value(target.latitude, target.longitude)
            if (currentCamerasEnabled.value) {
                val halfSpan = map.projection.visibleRegion.latLngBounds.latitudeSpan / 2.0 * 111_320.0
                camerasViewModel.onViewportChanged(target.latitude, target.longitude, halfSpan)
            }
        }
        map.addOnMapClickListener(click)
        map.addOnMapLongClickListener(longClick)
        map.addOnCameraIdleListener(idle)
        onDispose {
            map.removeOnMapClickListener(click)
            map.removeOnMapLongClickListener(longClick)
            map.removeOnCameraIdleListener(idle)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mv = mapView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onDestroy()
            mapView = null
            rawMap = null
            mapLibreMap = null
        }
    }

    // Forward memory pressure to the MapView so it can release GL/tile caches,
    // as the MapLibre quickstart requires. onLowMemory was never wired before.
    DisposableEffect(mapView) {
        val mv = mapView
        @Suppress(
            "DEPRECATION"
        ) // memory-pressure constants/onLowMemory; alternatives are also deprecated
        val callbacks =
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mv?.onLowMemory()
                }

                override fun onLowMemory() {
                    mv?.onLowMemory()
                }

                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) =
                    Unit
            }
        context.applicationContext.registerComponentCallbacks(callbacks)
        onDispose { context.applicationContext.unregisterComponentCallbacks(callbacks) }
    }
}

private fun configureMap(map: MapLibreMap, initialCenter: GeoPoint? = null) {
    val ui = map.uiSettings
    ui.isCompassEnabled = false
    ui.isLogoEnabled = false
    ui.isAttributionEnabled = false

    map.setMaxZoomPreference(MAX_ZOOM)
    map.setMinZoomPreference(MIN_ZOOM)

    map.cameraPosition =
        CameraPosition.Builder()
            .target(initialCenter?.let { LatLng(it.latitude, it.longitude) } ?: AdelaideCbd)
            .zoom(ADELAIDE_ZOOM)
            .bearing(0.0)
            .tilt(0.0)
            .build()
}

private fun identifyFeature(
    context: android.content.Context,
    map: MapLibreMap,
    latLng: LatLng,
): IdentifiedFeature? {
    val screenPoint = map.projection.toScreenLocation(latLng)
    return IDENTIFY_LAYERS.firstNotNullOfOrNull { layer ->
        val hits = map.queryRenderedFeatures(screenPoint, layer.id)
        hits.firstNotNullOfOrNull { hit ->
            val props = hit.properties() ?: return@firstNotNullOfOrNull null
            val name =
                (props["name:en"] ?: props["name"] ?: props["name:latin"])?.asString?.takeIf {
                    it.isNotBlank()
                } ?: return@firstNotNullOfOrNull null
            IdentifiedFeature(
                name = name,
                kind = context.getString(layer.kindRes),
                sourceLayer = layer.sourceLayer,
                featureClass = props["class"]?.takeUnless { it.isJsonNull }?.asString,
                latitude = latLng.latitude,
                longitude = latLng.longitude,
            )
        }
    }
}

private fun SearchResult.asIdentifiedFeature(context: Context) =
    IdentifiedFeature(
        name = name,
        kind = context.getString(R.string.feature_poi),
        sourceLayer = "poi",
        featureClass = className,
        latitude = latitude,
        longitude = longitude,
    )

private data class IdentifiedFeature(
    val name: String,
    val kind: String,
    val sourceLayer: String,
    val featureClass: String?,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

@Composable
private fun FeatureSheetBody(
    feature: IdentifiedFeature,
    onNavigate: ((Double, Double) -> Unit)? = null,
    onSave: ((Double, Double) -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
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
        if (!feature.featureClass.isNullOrBlank()) {
            Text(
                text =
                    feature.featureClass.substringAfter('=').replace('_', ' ').replaceFirstChar {
                        it.uppercase()
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = feature.sourceLayer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onSave != null) {
                OutlinedButton(onClick = { onSave(feature.latitude, feature.longitude) }) {
                    Icon(
                        Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(stringResource(R.string.action_save))
                }
            }
            if (onNavigate != null) {
                Button(onClick = { onNavigate(feature.latitude, feature.longitude) }) {
                    Text(stringResource(R.string.route_directions))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Static coverage banner shown when the map is rendering the bundled Adelaide demo tiles (no full
 * SA pack installed). Lightweight translucent chip, matching the attribution overlay's visual
 * language so the top of the map stays calm.
 */
@Composable
private fun DemoCoverageBanner(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xE6263238))
                .padding(horizontal = 12.dp, vertical = 6.dp)
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
private fun OsmAttributionOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xCCFFFFFF))
                .padding(horizontal = 8.dp, vertical = 4.dp)
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
        modifier = modifier.fillMaxSize().padding(24.dp),
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
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
    ) {
        Icon(
            imageVector =
                if (isLocated) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
            contentDescription = stringResource(R.string.map_my_location),
            // The single "you" accent only when a live fix is shown.
            tint = if (isLocated) UserAccentBlue else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val UserAccentBlue = Color(0xFF1E88E5)

/**
 * True only when the app actually declares a location permission in its merged manifest. The
 * `offline` product flavor strips ACCESS_*_LOCATION, so location UI must be hidden there —
 * requesting a permission absent from the manifest is instantly denied and would leave a dead
 * button.
 */
private fun isLocationFeatureDeclared(context: Context): Boolean = runCatching {
    context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        ?.contains(Manifest.permission.ACCESS_FINE_LOCATION) == true
}
    .getOrDefault(false)

/**
 * Recenters the camera on the user and the live-location stream for the blue dot are
 * flavor-specific: the real fused-location implementations live in the withNetwork source set, and
 * the offline flavor provides no-op twins (see
 * src/{withNetwork,offline}/.../ui/map/LocationProviders.kt). This keeps Google Play Services and
 * all location code out of the privacy-first offline build.
 */

// Default colour for a newly-saved place (one dedicated "saved" green, kept
// distinct from the traffic-severity hues and the "you" blue).
const val PIN_SAVE_COLOR = "#1B5E20"

// Pin colour choices — deliberately avoid the traffic-severity crayons
// (#4CAF50/#FF9800/#F44336/#9C27B0) and the user-location blue so a pin is never
// mistaken for a traffic marker or the position dot.
private val PIN_PALETTE =
    listOf(
        "#1B5E20", // green
        "#00838F", // cyan
        "#6D4C41", // brown
        "#C2185B", // magenta
        "#455A64", // slate
        "#283593", // indigo
    )

private val MicroLabelFont = androidx.compose.ui.text.font.FontFamily.Monospace

/**
 * Long-press "drop a pin here" sheet. Reverse-geocodes the point to a suggested name (offline),
 * lets the user adjust name + colour, and persists only on Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList")
@Composable
private fun DropPinSheet(
    latitude: Double,
    longitude: Double,
    reverseGeocode: suspend (Double, Double) -> String?,
    onDismiss: () -> Unit,
    onSave: (name: String, color: String) -> Unit,
    onDirections: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(PIN_SAVE_COLOR) }
    var place by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf(false) }

    LaunchedEffect(latitude, longitude) {
        val suggestion = reverseGeocode(latitude, longitude)
        place = suggestion
        if (name.isBlank() && suggestion != null) name = suggestion
        resolved = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
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
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.pin_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ColorChoiceRow(
                colors = PIN_PALETTE,
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDirections) {
                    Text(stringResource(R.string.route_directions))
                }
                Button(
                    onClick = {
                        val finalName =
                            name.trim().ifBlank {
                                place ?: "%.4f, %.4f".format(latitude, longitude)
                            }
                        onSave(finalName, selectedColor)
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Saved-pin detail sheet shown when a pin marker is tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinDetailSheet(
    pin: Pin,
    onDismiss: () -> Unit,
    onDirections: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val pinColor =
                remember(pin.color) {
                    runCatching { Color(android.graphics.Color.parseColor(pin.color)) }
                        .getOrDefault(Color.Gray)
                }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(pinColor))
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.pin_delete),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Button(onClick = onDirections) {
                    Text(stringResource(R.string.route_directions))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ColorChoiceRow(
    colors: List<String>,
    selectedColor: String,
    onColorSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        colors.forEach { colorHex ->
            val isSelected = colorHex == selectedColor
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(colorHex)))
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.onSurface,
                                    CircleShape,
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable(onClickLabel = colorHex) { onColorSelected(colorHex) }
            )
        }
    }
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

/** Detail sheet shown when a speed-camera marker is tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraDetailSheet(
    camera: SpeedCameraPoint,
    onDismiss: () -> Unit,
    onDirections: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.map_camera_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                text =
                    camera.maxspeedKmh?.let {
                        stringResource(R.string.map_camera_limit, it)
                    } ?: stringResource(R.string.map_camera_limit_unknown),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            camera.wayName
                ?.takeIf { it.isNotBlank() }
                ?.let { way ->
                    Text(
                        text = way,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            Text(
                text = "%.5f, %.5f".format(camera.latitude, camera.longitude).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = MicroLabelFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onDirections,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.route_directions))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
