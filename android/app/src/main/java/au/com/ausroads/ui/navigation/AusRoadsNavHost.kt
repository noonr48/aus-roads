/*
 * Top-level navigation destinations for aus-roads.
 * v0.1.1: Map / Pins / Settings / About.
 */
package au.com.ausroads.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import au.com.ausroads.R
import au.com.ausroads.feature.navigation.NavigationViewModel
import au.com.ausroads.feature.search.SearchViewModel
import au.com.ausroads.feature.traffic.TrafficViewModel
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.ui.about.AboutScreen
import au.com.ausroads.ui.explore.ExploreScreen
import au.com.ausroads.ui.explore.MapExploreViewModel
import au.com.ausroads.ui.map.MapScreen
import au.com.ausroads.ui.map.RouteHistoryViewModel
import au.com.ausroads.ui.map.RouteViewModel
import au.com.ausroads.ui.pins.PinListViewModel
import au.com.ausroads.ui.pins.PinsScreen
import au.com.ausroads.ui.settings.SettingsScreen
import au.com.ausroads.ui.settings.SettingsViewModel

sealed class AusRoadsDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    data object Map :
        AusRoadsDestination(
            route = "map",
            labelRes = R.string.nav_map,
            icon = Icons.Filled.Map,
        )

    data object Nearby :
        AusRoadsDestination(
            route = "nearby",
            labelRes = R.string.nav_explore,
            icon = Icons.Filled.Explore,
        )

    data object Pins :
        AusRoadsDestination(
            route = "pins",
            labelRes = R.string.nav_pins,
            icon = Icons.Filled.Place,
        )

    data object Settings :
        AusRoadsDestination(
            route = "settings",
            labelRes = R.string.nav_settings,
            icon = Icons.Filled.Settings,
        )

    data object About :
        AusRoadsDestination(
            route = "about",
            labelRes = R.string.about_title,
            icon = Icons.Filled.Settings, // Not in bottom bar, icon unused
        )

    companion object {
        // lazy is REQUIRED: eager initialization here runs during the sealed
        // class's <clinit>, touching subclass data objects before super-class
        // initialization completes (JVM super-first rule) -> INSTANCE-null NPE.
        // Plain-JVM unit tests (ResolveStartDestinationTest) exposed this;
        // deferring to first access is safe under any load order.
        val bottomBarItems: List<AusRoadsDestination> by lazy {
            listOf(Map, Nearby, Pins, Settings)
        }

        /**
         * Every route with a registered composable(...) destination in [AusRoadsNavHost]: the tab
         * destinations plus legitimate full-screen routes (About) excluded from [bottomBarItems].
         */
        val graphRoutes: Set<String> by lazy {
            setOf(
                Map.route,
                Nearby.route,
                Pins.route,
                Settings.route,
                About.route,
            )
        }
    }
}

@Composable
fun AusRoadsNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    onNavigationActiveChanged: (Boolean) -> Unit = {},
) {
    // Activity-scoped, outside destination entries: one area/selection across tabs.
    val explorationContext: MapExploreViewModel = hiltViewModel()
    val mapArea by explorationContext.reference.collectAsState()
    val selectedPlace by explorationContext.selection.collectAsState()
    // Same Activity owner as MainActivity's theme reader, never a destination-local replica.
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val settings by settingsViewModel.settings.collectAsState()
    val settingsStore by settingsViewModel.storeState.collectAsState()
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(AusRoadsDestination.Map.route) {
            val pinsViewModel: PinListViewModel = hiltViewModel()
            val searchViewModel: SearchViewModel = hiltViewModel()
            val trafficViewModel: TrafficViewModel = hiltViewModel()
            val navigationViewModel: NavigationViewModel = hiltViewModel()
            val routeHistoryViewModel: RouteHistoryViewModel = hiltViewModel()
            val routeViewModel: RouteViewModel = hiltViewModel()
            val pins by pinsViewModel.pins.collectAsState()
            // Initial display defaults are not confirmed guidance preferences.
            LaunchedEffect(settings.ttsEnabled, settingsStore.hasReadValue) {
                navigationViewModel.setTtsEnabled(settingsStore.hasReadValue && settings.ttsEnabled)
            }
            val routeAvoidOptions =
                RouteOptions(
                    avoidTolls = settings.avoidTolls,
                    avoidUnsealed = settings.avoidUnsealed,
                    avoidFerries = settings.avoidFerries,
                )
            // Seed the routing engine with the persisted avoid-preferences and
            // recompute the active route whenever they change.
            LaunchedEffect(routeAvoidOptions, settingsStore.hasReadValue) {
                if (settingsStore.hasReadValue) routeViewModel.setOptions(routeAvoidOptions)
            }
            MapScreen(
                onOpenSettings = { navController.navigate(AusRoadsDestination.Settings.route) },
                pins = pins,
                // Named args: addPinAt(longitude, latitude, ...) — passing
                // positionally here previously transposed every dropped pin.
                onSavePin = { lat, lon, name, color, onSaved ->
                    pinsViewModel.addPinAt(
                        latitude = lat,
                        longitude = lon,
                        name = name,
                        color = color,
                        onSaved = onSaved,
                    )
                },
                onDeletePin = { pin -> pinsViewModel.delete(pin) },
                reverseGeocode = { lat, lon -> searchViewModel.nearestPlaceName(lat, lon) },
                showAttribution = settings.showAttributionOverlay,
                searchViewModel = searchViewModel,
                trafficViewModel = trafficViewModel,
                trafficEnabled = settings.liveTrafficEnabled,
                navigationViewModel = navigationViewModel,
                routeHistoryViewModel = routeHistoryViewModel,
                routeViewModel = routeViewModel,
                routeAvoidOptions = routeAvoidOptions,
                onRouteAvoidOptionsChange = { opts ->
                    settingsViewModel.setAvoidOptions(
                        avoidTolls = opts.avoidTolls,
                        avoidUnsealed = opts.avoidUnsealed,
                        avoidFerries = opts.avoidFerries,
                    )
                },
                camerasEnabled = settings.speedCamerasEnabled,
                onNavigationActiveChanged = onNavigationActiveChanged,
                initialCenter = mapArea,
                requestedPlace = selectedPlace,
                onPlaceShown = explorationContext::consumeSelection,
                onMapAreaChanged = explorationContext::updateCenter,
            )
        }
        composable(AusRoadsDestination.Nearby.route) {
            ExploreScreen(
                reference = mapArea,
                onShowOnMap = { result ->
                    explorationContext.openOnMap(result)
                    // A natural Back returns to this exact discovery state, not an external app.
                    navController.navigate(AusRoadsDestination.Map.route) { launchSingleTop = true }
                },
            )
        }
        composable(AusRoadsDestination.Pins.route) {
            PinsScreen()
        }
        composable(AusRoadsDestination.Settings.route) {
            SettingsScreen(
                onOpenAbout = { navController.navigate(AusRoadsDestination.About.route) },
                viewModel = settingsViewModel,
            )
        }
        composable(AusRoadsDestination.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Validates legacy explicit destination values; unknown strings fall back to Map.
 *
 * Normal phone/tablet rendering now uses a STABLE Map graph root and lets NavController restore its
 * back stack. Recreating the graph root from every current entry breaks the internal
 * discovery-to-map Back journey, as the actual Android regression test demonstrated.
 */
fun resolveStartDestination(observedRoute: String?): String =
    if (observedRoute != null && observedRoute in AusRoadsDestination.graphRoutes) {
        observedRoute
    } else {
        AusRoadsDestination.Map.route
    }
