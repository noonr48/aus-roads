/*
 * Top-level navigation destinations for aus-roads.
 * v0.1.1: Map / Pins / Settings / About.
 */
package au.com.ausroads.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import au.com.ausroads.R
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.ui.about.AboutScreen
import au.com.ausroads.ui.map.MapScreen
import au.com.ausroads.ui.map.RouteHistoryViewModel
import au.com.ausroads.ui.map.RouteOptionsWriteRecovery
import au.com.ausroads.ui.pins.PinListViewModel
import au.com.ausroads.ui.pins.PinListUiState
import au.com.ausroads.ui.pins.PinsScreen
import au.com.ausroads.ui.settings.SettingsScreen
import au.com.ausroads.ui.settings.SettingsViewModel
import au.com.ausroads.ui.settings.SettingsField
import au.com.ausroads.ui.settings.SettingsWriteErrorSnackbar
import au.com.ausroads.ui.settings.MapPackViewModel
import au.com.ausroads.feature.search.SearchViewModel
import au.com.ausroads.feature.traffic.TrafficViewModel
import au.com.ausroads.feature.navigation.NavigationViewModel
import au.com.ausroads.ui.map.RouteViewModel
import au.com.ausroads.ui.nearby.NearbyScreen
import au.com.ausroads.offline.search.SearchResult

sealed class AusRoadsDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    data object Map : AusRoadsDestination(
        route = "map",
        labelRes = R.string.nav_map,
        icon = Icons.Filled.LocationOn,
    )

    data object Nearby : AusRoadsDestination(
        route = "nearby",
        labelRes = R.string.nav_nearby,
        icon = Icons.Filled.NearMe,
    )

    data object Pins : AusRoadsDestination(
        route = "pins",
        labelRes = R.string.nav_pins,
        icon = Icons.Filled.Place,
    )

    data object Settings : AusRoadsDestination(
        route = "settings",
        labelRes = R.string.nav_settings,
        icon = Icons.Filled.Settings,
    )

    data object About : AusRoadsDestination(
        route = "about",
        labelRes = R.string.about_title,
        icon = Icons.Filled.Settings, // Not in bottom bar, icon unused
    )

    companion object {
        val bottomBarItems: List<AusRoadsDestination> = listOf(Map, Nearby, Pins, Settings)
    }
}

@Composable
fun AusRoadsNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    var requestedMapTarget by remember { mutableStateOf<SearchResult?>(null) }
    // Resolve this before NavHost supplies a destination owner so Map, Settings,
    // and the activity theme share one write/error recovery stream.
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        // A navigation graph's start must be immutable. Following the current
        // route here rebuilt the graph on every tab switch and could tear down
        // the native map surface while MapLibre was still initialising.
        startDestination = AusRoadsDestination.Map.route,
        modifier = modifier,
    ) {
        composable(AusRoadsDestination.Map.route) {
            val pinsViewModel: PinListViewModel = hiltViewModel()
            val searchViewModel: SearchViewModel = hiltViewModel()
            val trafficViewModel: TrafficViewModel = hiltViewModel()
            val navigationViewModel: NavigationViewModel = hiltViewModel()
            val routeHistoryViewModel: RouteHistoryViewModel = hiltViewModel()
            val routeViewModel: RouteViewModel = hiltViewModel()
            val mapPackViewModel: MapPackViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsState()
            val settingsWriteError by settingsViewModel.writeError.collectAsState()
            val pinListState by pinsViewModel.uiState.collectAsState()
            val pins = (pinListState as? PinListUiState.Content)?.pins.orEmpty()
            val installedPack by mapPackViewModel.renderInstalled.collectAsState()
            val installedStateInitialized by mapPackViewModel.renderStateInitialized.collectAsState()
            // Honour the TTS toggle: voice guidance previously spoke regardless.
            LaunchedEffect(settings.ttsEnabled) {
                navigationViewModel.setTtsEnabled(settings.ttsEnabled)
            }
            val routeAvoidOptions = RouteOptions(
                avoidTolls = settings.avoidTolls,
                avoidUnsealed = settings.avoidUnsealed,
                avoidFerries = settings.avoidFerries,
            )
            // Seed the routing engine with the persisted avoid-preferences and
            // recompute the active route whenever they change.
            LaunchedEffect(routeAvoidOptions) {
                routeViewModel.setOptions(routeAvoidOptions)
            }
            Box {
                MapScreen(
                    onOpenSettings = { navController.navigate(AusRoadsDestination.Settings.route) },
                    pins = pins,
                    // Named args: addPinAt(longitude, latitude, ...) — passing
                    // positionally here previously transposed every dropped pin.
                    onSavePin = { lat, lon, name, color, onSaved, onError ->
                        pinsViewModel.addPinAt(
                            latitude = lat,
                            longitude = lon,
                            name = name,
                            color = color,
                            onSaved = onSaved,
                            onError = onError,
                        )
                    },
                    onDeletePin = { pin, onDeleted, onError ->
                        pinsViewModel.delete(
                            pin = pin,
                            onDeleted = onDeleted,
                            onError = onError,
                        )
                    },
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
                    routeOptionsWriteRecovery = settingsWriteError
                        ?.takeIf { it.setting == SettingsField.RoutePreferences }
                        ?.let { error ->
                            RouteOptionsWriteRecovery(
                                isRetrying = error.isRetrying,
                                onRetry = settingsViewModel::retryFailedWrite,
                                onDismiss = settingsViewModel::dismissWriteError,
                            )
                        },
                    activePack = installedPack,
                    packStateInitialized = installedStateInitialized,
                    requestedMapTarget = requestedMapTarget,
                    onRequestedMapTargetConsumed = { requestedMapTarget = null },
                )
                settingsWriteError?.let { error ->
                    SettingsWriteErrorSnackbar(
                        error = error,
                        onRetry = settingsViewModel::retryFailedWrite,
                        onDismiss = settingsViewModel::dismissWriteError,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    )
                }
            }
        }
        composable(AusRoadsDestination.Nearby.route) {
            NearbyScreen(
                onManageMapPack = {
                    navController.navigate(AusRoadsDestination.Settings.route)
                },
                onViewOnMap = { result ->
                    requestedMapTarget = result
                    navController.navigate(AusRoadsDestination.Map.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
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
