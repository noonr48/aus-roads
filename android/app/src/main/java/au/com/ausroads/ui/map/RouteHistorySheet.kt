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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import au.com.ausroads.R
import au.com.ausroads.data.routes.RouteHistoryEntity

typealias RouteHistoryDeleteAction = (
    route: RouteHistoryEntity,
    onDeleted: () -> Unit,
    onError: (Throwable) -> Unit,
) -> Unit

data class RouteHistorySheetState(
    val routes: List<RouteHistoryEntity>,
    val isLoading: Boolean = false,
    val hasLoadError: Boolean = false,
)

data class RouteHistorySheetActions(
    val onRetryLoad: () -> Unit = {},
    val onRouteSelected: (RouteHistoryEntity) -> Unit,
    val onDismiss: () -> Unit,
    val onDelete: RouteHistoryDeleteAction? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteHistorySheet(
    state: RouteHistorySheetState,
    actions: RouteHistorySheetActions,
    modifier: Modifier = Modifier,
) {
    var routePendingDelete by remember { mutableStateOf<RouteHistoryEntity?>(null) }
    var deletingRouteId by remember { mutableStateOf<Long?>(null) }
    var deleteErrorRoute by remember { mutableStateOf<RouteHistoryEntity?>(null) }
    val currentDeletingRouteId by rememberUpdatedState(deletingRouteId)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            target != SheetValue.Hidden || currentDeletingRouteId == null
        },
    )
    val performDelete: (RouteHistoryEntity) -> Unit = { route ->
        deletingRouteId = route.id
        deleteErrorRoute = null
        actions.onDelete?.invoke(
            route,
            { deletingRouteId = null },
            { error ->
                android.util.Log.e("RouteHistorySheet", "Deleting route history failed", error)
                deletingRouteId = null
                deleteErrorRoute = route
            },
        )
    }
    ModalBottomSheet(
        onDismissRequest = { if (deletingRouteId == null) actions.onDismiss() },
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

            if (state.isLoading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.route_history_loading))
                }
            } else if (state.hasLoadError) {
                RouteHistoryLoadFailure(onRetry = actions.onRetryLoad)
            } else if (state.routes.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                deleteErrorRoute?.let { route ->
                    RouteDeleteFailure(
                        destination = route.destinationLabel(),
                        onRetry = { performDelete(route) },
                    )
                }
                LazyColumn {
                    items(state.routes) { route ->
                        RouteHistoryItem(
                            route = route,
                            onClick = { actions.onRouteSelected(route) },
                            onDelete = actions.onDelete?.let { { routePendingDelete = route } },
                            deleteState = when {
                                deletingRouteId == route.id -> RouteDeleteUiState.DELETING
                                deletingRouteId != null -> RouteDeleteUiState.BLOCKED
                                else -> RouteDeleteUiState.IDLE
                            },
                        )
                    }
                }
            }
        }
    }

    routePendingDelete?.let { route ->
        AlertDialog(
            onDismissRequest = { routePendingDelete = null },
            title = {
                Text(stringResource(R.string.route_history_delete_confirm_title, route.destinationLabel()))
            },
            text = { Text(stringResource(R.string.route_history_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        routePendingDelete = null
                        performDelete(route)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.pin_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { routePendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RouteHistoryLoadFailure(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.route_history_load_failed),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

private enum class RouteDeleteUiState {
    IDLE,
    DELETING,
    BLOCKED,
}

@Composable
private fun RouteHistoryItem(
    route: RouteHistoryEntity,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    deleteState: RouteDeleteUiState = RouteDeleteUiState.IDLE,
    modifier: Modifier = Modifier,
) {
    val isDeleting = deleteState == RouteDeleteUiState.DELETING
    val enabled = deleteState == RouteDeleteUiState.IDLE
    val destLabel = route.destinationLabel()
    val selectDescription = stringResource(R.string.route_history_select, destLabel)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = selectDescription
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = destLabel,
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
                IconButton(onClick = onDelete, enabled = enabled) {
                    if (isDeleting) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.route_history_delete, destLabel),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteDeleteFailure(
    destination: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.route_history_delete_failed, destination),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

internal fun RouteHistoryEntity.destinationLabel(): String =
    destName.ifEmpty { "%.4f, %.4f".format(destLat, destLon) }
