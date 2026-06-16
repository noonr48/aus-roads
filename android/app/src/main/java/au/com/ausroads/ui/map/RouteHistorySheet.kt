package au.com.ausroads.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import au.com.ausroads.R
import au.com.ausroads.data.routes.RouteHistoryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteHistorySheet(
    routes: List<RouteHistoryEntity>,
    onRouteSelected: (RouteHistoryEntity) -> Unit,
    onDismiss: () -> Unit,
    onDelete: ((RouteHistoryEntity) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.route_history_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            if (routes.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(routes) { route ->
                        RouteHistoryItem(
                            route = route,
                            onClick = { onRouteSelected(route) },
                            onDelete = onDelete?.let { { it(route) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteHistoryItem(
    route: RouteHistoryEntity,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val destLabel = route.destName.ifEmpty { "%.4f, %.4f".format(route.destLat, route.destLon) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = destLabel
            }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = route.destName.ifEmpty { "%.4f, %.4f".format(route.destLat, route.destLon) },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (route.originName.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.route_history_from, route.originName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.route_distance_km, route.distanceMeters / 1000.0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.route_duration_min, route.durationSeconds / 60.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.pin_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
