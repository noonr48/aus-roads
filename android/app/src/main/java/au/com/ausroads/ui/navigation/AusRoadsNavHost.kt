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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import au.com.ausroads.R
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.ui.about.AboutScreen
import au.com.ausroads.ui.map.MapScreen
import au.com.ausroads.ui.map.RouteHistoryViewModel
import au.com.ausroads.ui.pins.PinListViewModel
import au.com.ausroads.ui.pins.PinsScreen
import au.com.ausroads.ui.settings.SettingsScreen
import au.com.ausroads.ui.settings.SettingsViewModel
import au.com.ausroads.feature.search.SearchViewModel
import au.com.ausroads.feature.traffic.TrafficViewModel
import au.com.ausroads.feature.navigation.NavigationViewModel
import au.com.ausroads.ui.map.RouteViewModel
import au.com.ausroads.ui.nearby.NearbyScreen

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
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(AusRoadsDestination.Map.route) {
            val pinsViewModel: PinListViewModel = hiltViewModel()
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val searchViewModel: SearchViewModel = hiltViewModel()
            val trafficViewModel: TrafficViewModel = hiltViewModel()
            val navigationViewModel: NavigationViewModel = hiltViewModel()
            val routeHistoryViewModel: RouteHistoryViewModel = hiltViewModel()
            val routeViewModel: RouteViewModel = hiltViewModel()
            val settings by settingsViewModel.settings.collectAsState()
            val pins by pinsViewModel.pins.collectAsState()
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
            )
        }
        composable(AusRoadsDestination.Nearby.route) {
            NearbyScreen()
        }
        composable(AusRoadsDestination.Pins.route) {
            PinsScreen()
        }
        composable(AusRoadsDestination.Settings.route) {
            SettingsScreen(
                onOpenAbout = { navController.navigate(AusRoadsDestination.About.route) },
            )
        }
        composable(AusRoadsDestination.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
