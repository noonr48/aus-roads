/*
 * Root composable for aus-roads.
 * Hosts the bottom navigation (phone) or NavigationRail (tablet) and the NavHost.
 * v0.1 has three top-level destinations.
 */
package au.com.ausroads.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import au.com.ausroads.ui.navigation.AusRoadsDestination
import au.com.ausroads.ui.navigation.AusRoadsNavHost

@Composable
fun AusRoadsApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current
    val isTablet = context.resources.configuration.smallestScreenWidthDp >= 600

    if (isTablet) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                AusRoadsDestination.bottomBarItems.forEach { destination ->
                    val selected = backStackEntry?.destination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationRailItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = stringResource(destination.labelRes),
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
            AusRoadsNavHost(
                navController = navController,
                startDestination = backStackEntry?.destination?.route
                    ?: AusRoadsDestination.Map.route,
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    AusRoadsDestination.bottomBarItems.forEach { destination ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            AusRoadsNavHost(
                navController = navController,
                startDestination = backStackEntry?.destination?.route
                    ?: AusRoadsDestination.Map.route,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
