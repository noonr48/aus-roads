package au.com.ausroads.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import au.com.ausroads.R
import au.com.ausroads.feature.traffic.severityColor
import au.com.ausroads.traffic.provider.EventType
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.traffic.provider.Severity
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficEventSheet(
    event: LiveTrafficEvent,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        TrafficEventContent(event = event)
    }
}

@Composable
private fun TrafficEventContent(
    event: LiveTrafficEvent,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SeverityDot(severity = event.severity)
            Text(
                text = event.type.displayName(),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Text(
            text = event.description,
            style = MaterialTheme.typography.bodyLarge,
        )

        if (event.attributes.isNotEmpty()) {
            val road = event.attributes["LOCAL_ROAD"] ?: event.attributes["LOCAL_ROAD_NAME"]
            val suburb = event.attributes["SUBURB"] ?: event.attributes["START_SUBURB"]
            val speedLimit = event.attributes["SPEED_LIMIT"]

            if (road != null) {
                Text(
                    text = stringResource(R.string.traffic_road, road),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (suburb != null) {
                Text(
                    text = stringResource(R.string.traffic_suburb, suburb),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (speedLimit != null && speedLimit != NO_SPEED_RESTRICTION) {
                Text(
                    text = stringResource(R.string.traffic_speed_limit, speedLimit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        event.startTime?.let { start ->
            Text(
                text = stringResource(R.string.traffic_started, start.formatForDisplay()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        event.endTime?.let { end ->
            Text(
                text = stringResource(R.string.traffic_ends, end.formatForDisplay()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = event.attribution,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SeverityDot(severity: Severity, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(
        modifier = modifier.size(12.dp),
    ) {
        drawCircle(color = severityColor(severity))
    }
}

@Composable
private fun EventType.displayName(): String = when (this) {
    EventType.ROADWORKS -> stringResource(R.string.traffic_type_roadworks)
    EventType.INCIDENT -> stringResource(R.string.traffic_type_incident)
    EventType.CLOSURE -> stringResource(R.string.traffic_type_closure)
    EventType.DETOUR -> stringResource(R.string.traffic_type_detour)
    EventType.EVENT -> stringResource(R.string.traffic_type_event)
    EventType.OUTBACK_WARNING -> stringResource(R.string.traffic_type_outback)
    EventType.UNKNOWN -> stringResource(R.string.traffic_type_unknown)
}

private const val NO_SPEED_RESTRICTION = "No Restriction"

private fun Instant.formatForDisplay(): String {
    val local = this.toLocalDateTime(TimeZone.currentSystemDefault())
    return "${local.dayOfMonth} ${local.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${local.year}, " +
        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}
