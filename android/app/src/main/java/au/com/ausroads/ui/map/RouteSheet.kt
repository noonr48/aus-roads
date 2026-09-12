package au.com.ausroads.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import au.com.ausroads.R
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.routing.engine.RouteResult
import android.content.Context
import androidx.core.content.ContextCompat

/** True when POST_NOTIFICATIONS is declared-but-ungranted (API 33+ only). */
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // Compose screen entry point; params mirror MapScreenContent's pattern
@Composable
fun RouteSheet(
    result: RouteResult,
    onDismiss: () -> Unit,
    onAddWaypoint: ((String) -> Unit)? = null,
    navigationViewModel: au.com.ausroads.feature.navigation.NavigationViewModel? = null,
    avoidOptions: RouteOptions = RouteOptions(),
    onAvoidOptionsChange: (RouteOptions) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        RouteContent(
            result = result,
            onDismiss = onDismiss,
            onAddWaypoint = onAddWaypoint,
            navigationViewModel = navigationViewModel,
            avoidOptions = avoidOptions,
            onAvoidOptionsChange = onAvoidOptionsChange,
        )
    }
}

@Suppress("LongParameterList") // mirrors RouteSheet's parameter set
@Composable
private fun RouteContent(
    result: RouteResult,
    onDismiss: () -> Unit,
    onAddWaypoint: ((String) -> Unit)? = null,
    navigationViewModel: au.com.ausroads.feature.navigation.NavigationViewModel? = null,
    avoidOptions: RouteOptions = RouteOptions(),
    onAvoidOptionsChange: (RouteOptions) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val distanceKm = result.distanceMeters / 1000.0
        val durationMin = result.durationSeconds / 60.0

        Text(
            text = stringResource(R.string.route_distance_duration, distanceKm, durationMin),
            style = MaterialTheme.typography.headlineSmall,
        )

        RouteOptionsSection(
            options = avoidOptions,
            onChange = onAvoidOptionsChange,
        )

        if (onAddWaypoint != null) {
            WaypointInput(onAddWaypoint = onAddWaypoint)
        }

        if (result.maneuvers.isNotEmpty()) {
            Text(
                text = stringResource(R.string.route_directions),
                style = MaterialTheme.typography.titleMedium,
            )
            result.maneuvers.forEach { maneuver ->
                Text(
                    text = maneuver.instruction,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (navigationViewModel != null) {
            val context = LocalContext.current
            // First-run gate: request location (then notifications on 13+) at the
            // point of use, so Start never lands on the refusal banner cold.
            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                navigationViewModel.startNavigation(result)
                onDismiss()
            }
            val locationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted && needsNotificationPermission(context)) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Denied (or pre-13/no ask needed): start anyway — the VM's
                    // permission guard publishes the honest, dismissible
                    // LocationUnavailable banner instead of a silent dead-end.
                    navigationViewModel.startNavigation(result)
                    onDismiss()
                }
            }
            Button(
                onClick = {
                    val hasLocation = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    when {
                        !hasLocation ->
                            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        needsNotificationPermission(context) ->
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> {
                            navigationViewModel.startNavigation(result)
                            onDismiss()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.route_start_navigation))
            }
        }

        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.route_close))
        }
    }
}

@Composable
private fun RouteOptionsSection(
    options: RouteOptions,
    onChange: (RouteOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.route_options_title),
            style = MaterialTheme.typography.titleMedium,
        )
        AvoidToggleRow(
            label = stringResource(R.string.route_avoid_tolls),
            checked = options.avoidTolls,
            onCheckedChange = { onChange(options.copy(avoidTolls = it)) },
        )
        AvoidToggleRow(
            label = stringResource(R.string.route_avoid_unsealed),
            checked = options.avoidUnsealed,
            onCheckedChange = { onChange(options.copy(avoidUnsealed = it)) },
        )
        AvoidToggleRow(
            label = stringResource(R.string.route_avoid_ferries),
            checked = options.avoidFerries,
            onCheckedChange = { onChange(options.copy(avoidFerries = it)) },
        )
    }
}

@Composable
private fun AvoidToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun WaypointInput(
    onAddWaypoint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var waypointText by remember { mutableStateOf("") }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = waypointText,
            onValueChange = { waypointText = it },
            label = { Text(stringResource(R.string.route_waypoint_hint)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        IconButton(
            onClick = {
                if (waypointText.isNotBlank()) {
                    onAddWaypoint(waypointText.trim())
                    waypointText = ""
                }
            },
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.route_add_waypoint),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RouteSheetPreview() {
    au.com.ausroads.ui.designsystem.AusRoadsTheme {
        RouteContent(
            result = RouteResult(
                distanceMeters = 15400,
                durationSeconds = 1020,
                geometry = emptyList(),
                maneuvers = listOf(
                    Maneuver(
                        instruction = "Head north on King William St",
                        lengthMeters = 1200,
                        durationSeconds = 120,
                        beginShapeIndex = 0,
                        streetName = "King William St",
                        maneuverType = "start",
                    ),
                    Maneuver(
                        instruction = "Turn right onto North Tce",
                        lengthMeters = 800,
                        durationSeconds = 90,
                        beginShapeIndex = 5,
                        streetName = "North Tce",
                        maneuverType = "right",
                    ),
                ),
            ),
            onDismiss = {},
            navigationViewModel = null,
        )
    }
}
