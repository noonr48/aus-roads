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
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import au.com.ausroads.R
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.data.pins.Pin
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

private const val STYLE_ASSET = "maptile/style.json"
private const val MBTILES_ASSET = "maptile/adelaide-test-tiles.mbtiles"
private const val MBTILES_FILENAME = "adelaide-test-tiles.mbtiles"

private val AdelaideCbd = LatLng(-34.92, 138.62)
private const val ADELAIDE_ZOOM = 11.0
// Allow zooming in past the tile maxzoom (overzoom) so POI labels (style minzoom
// 14) are tappable across a usable street-level range, not just a 14.0-14.5 sliver.
private const val MAX_ZOOM = 16.0
private const val MIN_ZOOM = 3.0

/**
 * Copies the bundled MBTiles from assets to filesDir if not already present,
 * then returns the style JSON with the correct `mbtiles://` absolute path.
 *
 * MapLibre Native's `mbtiles://` protocol requires a real filesystem path
 * (it uses `stat()` internally). Android assets are inside the APK and
 * don't have real paths, so we must copy to filesDir first.
 */
private fun ensureMbtilesAndStyle(context: Context): String {
    val mbtilesPath = resolveMbtilesPath(context)
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
private fun resolveMbtilesPath(context: Context): String {
    MapPackAvailability.installedPackMbtiles(context)?.let { return it.absolutePath }
    val outFile = File(context.filesDir, MBTILES_FILENAME)
    if (!outFile.exists()) {
        context.assets.open(MBTILES_ASSET).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
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
    IdentifyLayer("poi", R.string.feature_poi, "poi"),
    IdentifyLayer("road-label", R.string.feature_road, "transportation_name"),
    IdentifyLayer("place-suburb", R.string.feature_place, "place"),
)

@Suppress("LongParameterList")
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasPack = remember(context) { MapPackAvailability.hasAnyPack(context) }

    if (!hasPack) {
        NoMapPackPlaceholder(
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    MapScreenContent(
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
        modifier = modifier,
    )
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreenContent(
    pins: List<Pin>,
    onSavePin: (Double, Double, String, String, (Pin) -> Unit) -> Unit,
    onDeletePin: (Pin) -> Unit,
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
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Start traffic polling when enabled
    LaunchedEffect(trafficEnabled) {
        if (trafficEnabled && trafficViewModel != null) {
            trafficViewModel.startPolling()
        }
    }

    // Traffic overlay on map (v0.2)
    val trafficEvents: List<LiveTrafficEvent> = if (trafficEnabled && trafficViewModel != null) {
        trafficViewModel.events.collectAsState().value
    } else {
        emptyList()
    }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var selectedFeature by remember { mutableStateOf<IdentifiedFeature?>(null) }
    var selectedTrafficEvent by remember { mutableStateOf<LiveTrafficEvent?>(null) }
    var selectedPin by remember { mutableStateOf<Pin?>(null) }
    var pendingDrop by remember { mutableStateOf<LatLng?>(null) }
    var showRouteHistory by remember { mutableStateOf(false) }

    // Location permission + live fix (drives the blue dot and the My Location FAB).
    // The `offline` flavor strips location from the manifest by design, so the whole
    // feature is hidden there rather than offering a dead button.
    val locationAvailable = remember(context) { isLocationFeatureDeclared(context) }
    var hasLocationPermission by remember {
        mutableStateOf(
            locationAvailable &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED,
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
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
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

    // Start directions to a coordinate, preferring the live GPS fix as the origin
    // and falling back to the current map centre when no fix is available.
    val startDirectionsTo: (Double, Double) -> Unit = startDirections@{ lat, lon ->
        val vm = routeViewModel ?: return@startDirections
        val originLat = userLocation.value?.latitude ?: mapLibreMap?.cameraPosition?.target?.latitude
        val originLon = userLocation.value?.longitude ?: mapLibreMap?.cameraPosition?.target?.longitude
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
                    mv.getMapAsync { m ->
                        val styleJson = ensureMbtilesAndStyle(ctx)
                        m.setStyle(Style.Builder().fromJson(styleJson)) {
                            configureMap(m)
                            // Publish the map only once the style is ready, so overlay
                            // effects (pins, user dot) that need `map.style` add their
                            // layers reliably — including for pins present at launch.
                            mapLibreMap = m
                        }
                    }
                    mapView = mv
                }
            },
            update = { /* lifecycle is forwarded via DisposableEffect below */ },
        )

        // Saved pins + live user-location layers (manage MapLibre layers, no UI)
        PinsMapOverlay(pins = pins, mapLibreMap = mapLibreMap)
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
        if (remember(context) { MapPackAvailability.isUsingBundledFallback(context) }) {
            DemoCoverageBanner(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp),
            )
        }

        // My Location FAB — recenters on the live fix, requests permission if
        // needed, and gives feedback when no fix is available yet. Hidden on the
        // offline flavor, which has no location permission by design.
        if (locationAvailable) {
            MyLocationFab(
                onClick = onMyLocation,
                isLocated = userLocation.value != null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 56.dp, end = 16.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
        )

        // Search overlay (v0.1.2)
        if (searchViewModel != null) {
            au.com.ausroads.feature.search.SearchOverlay(
                viewModel = searchViewModel,
                onResultSelected = { result ->
                    mapLibreMap?.animateCamera(
                        org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                            LatLng(result.latitude, result.longitude),
                            12.0,
                        ),
                        1000,
                    )
                    // Set route destination from search result
                    if (routeViewModel != null) {
                        val camera = mapLibreMap?.cameraPosition?.target
                        if (camera != null) {
                            routeViewModel.setOrigin(GeoPoint(longitude = camera.longitude, latitude = camera.latitude))
                        }
                        routeViewModel.setDestination(
                            GeoPoint(longitude = result.longitude, latitude = result.latitude),
                        )
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        // Traffic status pill (v0.2)
        if (trafficEnabled && trafficViewModel != null) {
            au.com.ausroads.feature.traffic.TrafficStatusPill(
                viewModel = trafficViewModel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 16.dp),
            )
        }

        // Traffic events rendered on map
        if (trafficEvents.isNotEmpty()) {
            TrafficMapOverlay(events = trafficEvents, mapLibreMap = mapLibreMap)
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
                    // Save completed route to history when navigation ends
                    val route = activeRoute
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
                    routeViewModel?.clearRoute()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // Route result sheet
        if (routeViewModel != null) {
            val route = (routeState as? RouteUiState.Active)?.result
            if (route != null) {
                RouteSheet(
                    result = route,
                    onDismiss = { routeViewModel.clearRoute() },
                    navigationViewModel = navigationViewModel,
                    avoidOptions = routeAvoidOptions,
                    onAvoidOptionsChange = onRouteAvoidOptionsChange,
                )
            }
        }

        // Route history button
        if (routeHistoryViewModel != null) {
            Button(
                onClick = { showRouteHistory = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, start = 16.dp),
            ) {
                Text(stringResource(R.string.map_history_button))
            }
        }

        // Route history sheet
        if (showRouteHistory && routeHistoryViewModel != null && routeHistoryRoutes != null) {
            RouteHistorySheet(
                routes = routeHistoryRoutes,
                onRouteSelected = { entity ->
                    if (routeViewModel != null) {
                        routeViewModel.clearRoute()
                        routeViewModel.setOrigin(GeoPoint(longitude = entity.originLon, latitude = entity.originLat))
                        routeViewModel.setDestination(GeoPoint(longitude = entity.destLon, latitude = entity.destLat))
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
                    onNavigate = if (routeViewModel != null) { lat, lon ->
                        startDirectionsTo(lat, lon)
                        selectedFeature = null
                    } else null,
                    onSave = { lat, lon ->
                        val name = feature.name
                        onSavePin(lat, lon, name, PIN_SAVE_COLOR) { saved ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.map_pin_saved, saved.name),
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
                            val result = snackbarHostState.showSnackbar(
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
                            context.getString(R.string.map_pin_deleted, pin.name),
                        )
                    }
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
            // 2) Traffic features (asString, not toString — same JSON-quote pitfall)
            val trafficHits = m.queryRenderedFeatures(screenPoint, "traffic-points")
            val hitId = trafficHits.firstOrNull()?.properties()?.get("id")
                ?.takeIf { !it.isJsonNull }?.asString
            if (hitId != null) {
                selectedTrafficEvent = currentTrafficEvents.value.firstOrNull { it.primaryKey == hitId }
                return@addOnMapClickListener true
            }
            // 3) Labelled map features (road / suburb / POI)
            val hit = identifyFeature(context, m, latLng)
            if (hit != null) selectedFeature = hit
            false
        }
        m.addOnMapLongClickListener { latLng ->
            pendingDrop = latLng
            true
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
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mv?.onLowMemory()
            }
            override fun onLowMemory() { mv?.onLowMemory() }
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

private fun identifyFeature(context: android.content.Context, map: MapLibreMap, latLng: LatLng): IdentifiedFeature? {
    val screenPoint = map.projection.toScreenLocation(latLng)
    return IDENTIFY_LAYERS.firstNotNullOfOrNull { layer ->
        val hits = map.queryRenderedFeatures(screenPoint, layer.id)
        hits.firstNotNullOfOrNull { hit ->
            val props = hit.properties() ?: return@firstNotNullOfOrNull null
            val name = (props["name:en"] ?: props["name"] ?: props["name:latin"])
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: return@firstNotNullOfOrNull null
            IdentifiedFeature(
                name = name,
                kind = context.getString(layer.kindRes),
                sourceLayer = layer.sourceLayer,
                featureClass = props["class"]?.toString(),
                latitude = latLng.latitude,
                longitude = latLng.longitude,
            )
        }
    }
}

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
        modifier = Modifier
            .fillMaxSize()
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
        if (!feature.featureClass.isNullOrBlank()) {
            Text(
                text = "${feature.sourceLayer} · class=${feature.featureClass}",
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
            if (onSave != null && feature.latitude != 0.0) {
                OutlinedButton(onClick = { onSave(feature.latitude, feature.longitude) }) {
                    Icon(
                        Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(stringResource(R.string.action_save))
                }
            }
            if (onNavigate != null && feature.latitude != 0.0) {
                Button(onClick = { onNavigate(feature.latitude, feature.longitude) }) {
                    Text(stringResource(R.string.route_start_navigation))
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
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = CircleShape,
    ) {
        Icon(
            imageVector = if (isLocated) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
            contentDescription = stringResource(R.string.map_my_location),
            // The single "you" accent only when a live fix is shown.
            tint = if (isLocated) UserAccentBlue else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val UserAccentBlue = Color(0xFF1E88E5)

/**
 * True only when the app actually declares a location permission in its merged
 * manifest. The `offline` product flavor strips ACCESS_*_LOCATION, so location UI
 * must be hidden there — requesting a permission absent from the manifest is
 * instantly denied and would leave a dead button.
 */
private fun isLocationFeatureDeclared(context: Context): Boolean = runCatching {
    context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        ?.contains(Manifest.permission.ACCESS_FINE_LOCATION) == true
}.getOrDefault(false)

/**
 * Recenters the camera on the user and the live-location stream for the blue dot
 * are flavor-specific: the real fused-location implementations live in the
 * withNetwork source set, and the offline flavor provides no-op twins (see
 * src/{withNetwork,offline}/.../ui/map/LocationProviders.kt). This keeps Google
 * Play Services and all location code out of the privacy-first offline build.
 */

// Default colour for a newly-saved place (one dedicated "saved" green, kept
// distinct from the traffic-severity hues and the "you" blue).
const val PIN_SAVE_COLOR = "#1B5E20"

// Pin colour choices — deliberately avoid the traffic-severity crayons
// (#4CAF50/#FF9800/#F44336/#9C27B0) and the user-location blue so a pin is never
// mistaken for a traffic marker or the position dot.
private val PIN_PALETTE = listOf(
    "#1B5E20", // green
    "#00838F", // cyan
    "#6D4C41", // brown
    "#C2185B", // magenta
    "#455A64", // slate
    "#283593", // indigo
)

private val MicroLabelFont = androidx.compose.ui.text.font.FontFamily.Monospace

/**
 * Long-press "drop a pin here" sheet. Reverse-geocodes the point to a suggested
 * name (offline), lets the user adjust name + colour, and persists only on Save.
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
                        val finalName = name.trim().ifBlank {
                            place ?: "%.4f, %.4f".format(latitude, longitude)
                        }
                        onSave(finalName, selectedColor)
                    },
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
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(android.graphics.Color.parseColor(colorHex)))
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(onClickLabel = colorHex) { onColorSelected(colorHex) },
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
