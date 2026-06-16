package au.com.ausroads.feature.traffic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
        modifier = modifier,
    )
}

@Composable
private fun TrafficStatusPillContent(
    eventCount: Int,
    lastUpdated: Instant?,
    isLoading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
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

            if (error != null) {
                Text(
                    text = stringResource(R.string.traffic_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(R.string.traffic_event_count, eventCount),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
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
        )
    }
}
