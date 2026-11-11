package au.com.ausroads.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import au.com.ausroads.R
import au.com.ausroads.routing.engine.Maneuver
import au.com.ausroads.routing.engine.RouteOptions
import au.com.ausroads.routing.engine.RouteResult

data class RouteOptionsWriteRecovery(
    val isRetrying: Boolean,
    val onRetry: () -> Unit,
    val onDismiss: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // Compose screen entry point; params mirror MapScreenContent's pattern
@Composable
fun RouteSheet(
    result: RouteResult,
    onDismiss: () -> Unit,
    onNavigationStarted: () -> Unit,
    onAddWaypoint: ((String) -> Unit)? = null,
    navigationViewModel: au.com.ausroads.feature.navigation.NavigationViewModel? = null,
    preciseLocationGranted: Boolean = false,
    preciseLocationNeedsSettings: Boolean = false,
    onRequestPreciseLocation: () -> Unit = {},
    avoidOptions: RouteOptions = RouteOptions(),
    onAvoidOptionsChange: (RouteOptions) -> Unit = {},
    optionsWriteRecovery: RouteOptionsWriteRecovery? = null,
    modifier: Modifier = Modifier,
) {
    var waypointText by remember(result) { mutableStateOf("") }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val currentHasWaypointDraft by rememberUpdatedState(waypointText.isNotBlank())
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden && currentHasWaypointDraft) {
                showDiscardConfirmation = true
                false
            } else {
                true
            }
        },
    )
    val requestDismiss: () -> Unit = {
        if (waypointText.isNotBlank()) {
            showDiscardConfirmation = true
        } else {
            onDismiss()
        }
    }
    val keepEditing: () -> Unit = {
        showDiscardConfirmation = false
    }
    LaunchedEffect(showDiscardConfirmation) {
        if (!showDiscardConfirmation) {
            sheetState.show()
        }
    }
    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        RouteContent(
            result = result,
            onDismiss = requestDismiss,
            onNavigationStarted = onNavigationStarted,
            onAddWaypoint = onAddWaypoint,
            waypointText = waypointText,
            onWaypointTextChange = { waypointText = it },
            navigationViewModel = navigationViewModel,
            preciseLocationGranted = preciseLocationGranted,
            preciseLocationNeedsSettings = preciseLocationNeedsSettings,
            onRequestPreciseLocation = onRequestPreciseLocation,
            avoidOptions = avoidOptions,
            onAvoidOptionsChange = onAvoidOptionsChange,
            optionsWriteRecovery = optionsWriteRecovery,
        )
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = keepEditing,
            title = { Text(stringResource(R.string.route_waypoint_discard_title)) },
            text = { Text(stringResource(R.string.route_waypoint_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        waypointText = ""
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.pin_discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = keepEditing) {
                    Text(stringResource(R.string.pin_keep_editing))
                }
            },
        )
    }
}

@Suppress("LongParameterList") // mirrors RouteSheet's parameter set
@Composable
private fun RouteContent(
    result: RouteResult,
    onDismiss: () -> Unit,
    onNavigationStarted: () -> Unit,
    onAddWaypoint: ((String) -> Unit)? = null,
    waypointText: String = "",
    onWaypointTextChange: (String) -> Unit = {},
    navigationViewModel: au.com.ausroads.feature.navigation.NavigationViewModel? = null,
    preciseLocationGranted: Boolean = false,
    preciseLocationNeedsSettings: Boolean = false,
    onRequestPreciseLocation: () -> Unit = {},
    avoidOptions: RouteOptions = RouteOptions(),
    onAvoidOptionsChange: (RouteOptions) -> Unit = {},
    optionsWriteRecovery: RouteOptionsWriteRecovery? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
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
        optionsWriteRecovery?.let { RouteOptionsWriteFailure(it) }

        if (onAddWaypoint != null) {
            WaypointInput(
                waypointText = waypointText,
                onWaypointTextChange = onWaypointTextChange,
                onAddWaypoint = onAddWaypoint,
            )
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
            if (!preciseLocationGranted) {
                Text(
                    text = stringResource(
                        if (preciseLocationNeedsSettings) {
                            R.string.route_precise_location_settings_explanation
                        } else {
                            R.string.route_precise_location_explanation
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    if (preciseLocationGranted) {
                        if (navigationViewModel.startNavigation(result)) onNavigationStarted()
                    } else {
                        onRequestPreciseLocation()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (preciseLocationGranted) {
                            R.string.route_start_navigation
                        } else {
                            if (preciseLocationNeedsSettings) {
                                R.string.route_open_location_settings
                            } else {
                                R.string.route_enable_precise_location
                            }
                        },
                    ),
                )
            }
        }

        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.route_close))
        }
    }
}

@Composable
private fun RouteOptionsWriteFailure(
    recovery: RouteOptionsWriteRecovery,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.settings_save_failed,
                stringResource(R.string.settings_route_preferences),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = recovery.onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
            TextButton(onClick = recovery.onRetry, enabled = !recovery.isRetrying) {
                Text(
                    stringResource(
                        if (recovery.isRetrying) R.string.settings_retrying else R.string.action_retry,
                    ),
                )
            }
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
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
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
            onCheckedChange = null,
        )
    }
}

@Composable
private fun WaypointInput(
    waypointText: String,
    onWaypointTextChange: (String) -> Unit,
    onAddWaypoint: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var waypointFocused by remember { mutableStateOf(false) }
    var waypointBlurred by remember { mutableStateOf(false) }
    val showWaypointError = waypointBlurred && waypointText.isBlank()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = waypointText,
            onValueChange = onWaypointTextChange,
            label = { Text(stringResource(R.string.route_waypoint_hint)) },
            isError = showWaypointError,
            supportingText = if (showWaypointError) {
                { Text(stringResource(R.string.route_waypoint_required)) }
            } else null,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    if (waypointFocused && !focusState.isFocused) waypointBlurred = true
                    waypointFocused = focusState.isFocused
                },
            singleLine = true,
        )
        IconButton(
            onClick = {
                if (waypointText.isNotBlank()) {
                    onAddWaypoint(waypointText.trim())
                    onWaypointTextChange("")
                }
            },
            enabled = waypointText.isNotBlank(),
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
            onNavigationStarted = {},
            navigationViewModel = null,
        )
    }
}
