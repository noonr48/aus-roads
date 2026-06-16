package au.com.ausroads.feature.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.com.ausroads.routing.engine.Maneuver

@Composable
fun NavigationOverlay(
    viewModel: NavigationViewModel,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val batteryWarning by viewModel.batteryWarning.collectAsState()

    // Keep screen on during navigation
    val view = LocalView.current
    DisposableEffect(state is NavigationState.Navigating || state is NavigationState.Recalculating) {
        view.keepScreenOn = state is NavigationState.Navigating || state is NavigationState.Recalculating
        onDispose { view.keepScreenOn = false }
    }

    when (val s = state) {
        is NavigationState.Idle -> {}
        is NavigationState.Navigating -> {
            Column(modifier = modifier) {
                if (batteryWarning) {
                    BatteryWarningBanner(modifier = Modifier.fillMaxWidth())
                }
                NavigationBanner(
                    maneuver = s.currentManeuver,
                    remainingDistance = s.remainingDistanceMeters,
                    remainingDuration = s.remainingDurationSeconds,
                    currentSpeed = s.currentSpeedKmh,
                    speedLimit = s.speedLimitKmh,
                    isOverspeeding = s.isOverspeeding,
                    onStop = {
                        viewModel.stopNavigation()
                        onStopNavigation()
                    },
                )
            }
        }
        is NavigationState.Recalculating -> {
            Column(modifier = modifier) {
                RecalculatingBanner(modifier = Modifier.fillMaxWidth())
                NavigationBanner(
                    maneuver = s.previousState.currentManeuver,
                    remainingDistance = s.previousState.remainingDistanceMeters,
                    remainingDuration = s.previousState.remainingDurationSeconds,
                    currentSpeed = s.previousState.currentSpeedKmh,
                    speedLimit = s.previousState.speedLimitKmh,
                    isOverspeeding = s.previousState.isOverspeeding,
                    onStop = {
                        viewModel.stopNavigation()
                        onStopNavigation()
                    },
                )
            }
        }
        is NavigationState.Arrived -> {
            ArrivalBanner(
                onStop = {
                    viewModel.stopNavigation()
                    onStopNavigation()
                },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun NavigationBanner(
    maneuver: Maneuver?,
    remainingDistance: Double,
    remainingDuration: Double,
    currentSpeed: Double,
    speedLimit: Int?,
    isOverspeeding: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Maneuver instruction
            Text(
                text = maneuver?.instruction ?: stringResource(R.string.nav_continue),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            // Street name
            maneuver?.streetName?.let { street ->
                Text(
                    text = street,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Distance and ETA
                val distKm = remainingDistance / 1000.0
                val durationMin = remainingDuration / 60.0
                Text(
                    text = "%.1f km · %.0f min".format(distKm, durationMin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                // Speed
                if (currentSpeed > 0) {
                    Text(
                        text = "%.0f km/h".format(currentSpeed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOverspeeding) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                // Speed limit
                if (speedLimit != null && speedLimit > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                            .border(2.dp, Color.Red, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "$speedLimit",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.Black,
                        )
                    }
                }

                // Stop button
                TextButton(onClick = onStop) {
                    Text(stringResource(R.string.nav_end))
                }
            }
        }
    }
}

@Composable
private fun ArrivalBanner(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.nav_arrived),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.nav_done))
            }
        }
    }
}

@Composable
private fun BatteryWarningBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.nav_battery_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun RecalculatingBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(R.string.nav_recalculating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
