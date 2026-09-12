package au.com.ausroads.feature.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.ausroads.routing.engine.Maneuver
import kotlin.math.roundToInt

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
                    nextManeuverDistanceMeters = s.nextManeuverDistanceMeters,
                    remainingDistance = s.remainingDistanceMeters,
                    remainingDuration = s.remainingDurationSeconds,
                    currentSpeed = s.currentSpeedKmh,
                    speedLimit = s.speedLimitKmh,
                    isOverspeeding = s.isOverspeeding,
                    awaitingGpsFix = s.awaitingGpsFix,
                    gpsLost = s.gpsLost,
                    cameraWarning = s.cameraWarning,
                    maneuverIndex = s.maneuverIndex,
                    totalManeuvers = s.totalManeuvers,
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
                    nextManeuverDistanceMeters = s.previousState.nextManeuverDistanceMeters,
                    remainingDistance = s.previousState.remainingDistanceMeters,
                    remainingDuration = s.previousState.remainingDurationSeconds,
                    currentSpeed = s.previousState.currentSpeedKmh,
                    speedLimit = s.previousState.speedLimitKmh,
                    isOverspeeding = s.previousState.isOverspeeding,
                    awaitingGpsFix = s.previousState.awaitingGpsFix,
                    gpsLost = s.previousState.gpsLost,
                    cameraWarning = s.previousState.cameraWarning,
                    maneuverIndex = s.previousState.maneuverIndex,
                    totalManeuvers = s.previousState.totalManeuvers,
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
        is NavigationState.LocationUnavailable -> {
            // Honest dead-end for the gms-free offline flavor (FINE location
            // stripped): surfacing beats silently simulating a drive. Unlike
            // the session states above, this one dismisses back to the route
            // sheet (no navigation ever started, so nothing to tear down).
            Column(modifier = modifier) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.nav_location_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { viewModel.dismissLocationUnavailable() },
                    ) {
                        Text(stringResource(R.string.nav_dismiss))
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationBanner(
    maneuver: Maneuver?,
    nextManeuverDistanceMeters: Double?,
    remainingDistance: Double,
    remainingDuration: Double,
    currentSpeed: Double,
    speedLimit: Int?,
    isOverspeeding: Boolean,
    awaitingGpsFix: Boolean,
    gpsLost: Boolean,
    cameraWarning: CameraWarning?,
    maneuverIndex: Int,
    totalManeuvers: Int,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Camera proximity warning — the most safety-relevant glance item, so
        // it sits above the maneuver card and uses the strongest contrast.
        cameraWarning?.let { warning ->
            CameraWarningBanner(warning = warning)
        }

        if (awaitingGpsFix) {
            AcquiringGpsBanner()
        } else if (gpsLost) {
            GpsLostBanner()
        }

        // Maneuver card: high-contrast surface (not a tinted container) so the
        // numbers stay legible in sun glare — white card / dark card per theme.
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Turn glyph — the thing a driver parses first.
                Icon(
                    imageVector = maneuverIcon(maneuver),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(44.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    nextManeuverDistanceMeters?.let { distance ->
                        Text(
                            text = formatDistance(distance),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = maneuver?.instruction ?: stringResource(R.string.nav_continue),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    maneuver?.streetName?.let { street ->
                        if (street.isNotBlank()) {
                            Text(
                                text = street,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Current speed, glance-sized, error-tinted when over the limit.
                Column(horizontalAlignment = Alignment.End) {
                    if (currentSpeed > 0) {
                        Text(
                            text = "%.0f".format(currentSpeed),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isOverspeeding) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "km/h",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    speedLimit?.takeIf { it > 0 }?.let { limit ->
                        Spacer(modifier = Modifier.height(4.dp))
                        SpeedLimitSign(limitKmh = limit)
                    }
                }
            }

            // Trip progress strip.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val distKm = remainingDistance / 1000.0
                val durationMin = remainingDuration / 60.0
                Text(
                    text = "%.1f km · %.0f min".format(distKm, durationMin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.nav_maneuver_progress,
                        maneuverIndex + 1,
                        totalManeuvers,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // End button: a critical control gets its own full-width target, away
        // from the glance data (was previously the 4th item in the info row).
        androidx.compose.material3.Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text(
                text = stringResource(R.string.nav_end),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/** Road-sign-styled speed limit: white disc, thick red ring, black number. */
@Composable
private fun SpeedLimitSign(limitKmh: Int) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(4.dp, Color(0xFFCC0000), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$limitKmh",
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            color = Color.Black,
        )
    }
}

@Composable
private fun CameraWarningBanner(warning: CameraWarning) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (warning.maxspeedKmh != null) {
                    stringResource(R.string.nav_camera_ahead_limit, warning.maxspeedKmh)
                } else {
                    stringResource(R.string.nav_camera_ahead)
                },
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            warning.wayName?.takeIf { it.isNotBlank() }?.let { way ->
                Text(
                    text = way,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
        Text(
            text = formatDistance(warning.distanceMeters),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}

@Composable
private fun AcquiringGpsBanner() {
    StatusBanner(
        text = stringResource(R.string.nav_acquiring_gps),
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        leading = {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
    )
}

@Composable
private fun GpsLostBanner() {
    StatusBanner(
        text = stringResource(R.string.nav_gps_lost),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun StatusBanner(
    text: String,
    containerColor: Color,
    contentColor: Color,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
}

/** Maps the routing maneuver onto a glance-readable turn glyph. */
private fun maneuverIcon(maneuver: Maneuver?): ImageVector {
    val type = maneuver?.maneuverType.orEmpty().lowercase()
    val instruction = maneuver?.instruction.orEmpty().lowercase()
    return when {
        "roundabout" in type || "rotary" in type ->
            Icons.AutoMirrored.Filled.RotateRight
        "uturn" in type || "u-turn" in instruction ->
            Icons.Filled.UTurnLeft
        "sharp left" in instruction -> Icons.Filled.TurnSharpLeft
        "sharp right" in instruction -> Icons.Filled.TurnSharpRight
        "slight left" in instruction || type == "merge left" -> Icons.Filled.TurnSlightLeft
        "slight right" in instruction || type == "merge right" -> Icons.Filled.TurnSlightRight
        "left" in instruction || "left" in type -> Icons.Filled.TurnLeft
        "right" in instruction || "right" in type -> Icons.Filled.TurnRight
        else -> Icons.Filled.ArrowUpward
    }
}

/** Metres under 1 km, else kilometres with one decimal — the glance format. */
internal fun formatDistance(meters: Double): String =
    if (meters < 1000.0) {
        "${meters.roundToInt()} m"
    } else {
        "%.1f km".format(meters / 1000.0)
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
    StatusBanner(
        text = stringResource(R.string.nav_battery_warning),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    )
}

@Composable
private fun RecalculatingBanner(modifier: Modifier = Modifier) {
    StatusBanner(
        text = stringResource(R.string.nav_recalculating),
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        leading = {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
    )
}
