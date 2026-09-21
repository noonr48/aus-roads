package au.com.ausroads.feature.traffic

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import au.com.ausroads.traffic.provider.Severity
import kotlinx.datetime.Instant

@Composable
fun TrafficStatusPill(
    viewModel: TrafficViewModel,
    onOpenTrafficSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val events by viewModel.events.collectAsState()
    val lastUpdated by viewModel.lastUpdated.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    TrafficStatusPillContent(
        eventCount = events.size,
        lastUpdated = lastUpdated,
        isLoading = isLoading,
        error = error,
        onRetry = viewModel::fetchTraffic,
        onOpenTrafficSettings = onOpenTrafficSettings,
        modifier = modifier,
    )
}

@Composable
private fun TrafficStatusPillContent(
    eventCount: Int,
    lastUpdated: Instant?,
    isLoading: Boolean,
    error: TrafficFailure?,
    onRetry: () -> Unit,
    onOpenTrafficSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (error != null) {
            val presentation = error.presentation()
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (presentation.regionLabel == null) {
                        stringResource(presentation.messageRes)
                    } else {
                        stringResource(presentation.messageRes, presentation.regionLabel)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = when (presentation.recovery) {
                        TrafficRecovery.RETRY -> onRetry
                        TrafficRecovery.OPEN_SETTINGS -> onOpenTrafficSettings
                    },
                ) {
                    Text(stringResource(presentation.actionRes))
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = stringResource(R.string.traffic_event_count, eventCount),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

internal enum class TrafficRecovery {
    RETRY,
    OPEN_SETTINGS,
}

internal data class TrafficFailurePresentation(
    @StringRes val messageRes: Int,
    val regionLabel: String?,
    @StringRes val actionRes: Int,
    val recovery: TrafficRecovery,
)

internal fun TrafficFailure.presentation(): TrafficFailurePresentation = when (this) {
    TrafficFailure.AllProvidersUnavailable -> TrafficFailurePresentation(
        messageRes = R.string.traffic_offline,
        regionLabel = null,
        actionRes = R.string.traffic_retry,
        recovery = TrafficRecovery.RETRY,
    )
    is TrafficFailure.InvalidCredentials -> TrafficFailurePresentation(
        messageRes = R.string.traffic_invalid_credentials,
        regionLabel = friendlyTrafficRegion(regionCode),
        actionRes = R.string.traffic_open_settings,
        recovery = TrafficRecovery.OPEN_SETTINGS,
    )
}

private fun friendlyTrafficRegion(regionCode: String): String = when (regionCode) {
    "AU-NSW" -> "NSW"
    "AU-VIC" -> "VIC"
    else -> regionCode.removePrefix("AU-")
}

fun severityColor(severity: Severity): Color = when (severity) {
    Severity.LOW -> Color(0xFF4CAF50)
    Severity.MEDIUM -> Color(0xFFFF9800)
    Severity.HIGH -> Color(0xFFF44336)
    Severity.CRITICAL -> Color(0xFF9C27B0)
}

@Preview(showBackground = true)
@Composable
private fun TrafficStatusPillPreview() {
    au.com.ausroads.ui.designsystem.AusRoadsTheme {
        TrafficStatusPillContent(
            eventCount = 12,
            lastUpdated = null,
            isLoading = false,
            error = null,
            onRetry = {},
            onOpenTrafficSettings = {},
        )
    }
}
