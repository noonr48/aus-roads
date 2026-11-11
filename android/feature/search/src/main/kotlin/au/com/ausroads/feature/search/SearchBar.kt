package au.com.ausroads.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import au.com.ausroads.offline.search.SearchPresentation
import au.com.ausroads.offline.search.SearchResult
import java.util.Locale
import kotlin.math.abs

@Composable
fun SearchOverlay(
    viewModel: SearchViewModel,
    onResultSelected: (SearchResult) -> Unit,
    onManageMapPack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchFailed by viewModel.searchFailed.collectAsState()
    val searchUnavailable by viewModel.searchUnavailable.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFieldShape = MaterialTheme.shapes.extraLarge
    val searchFieldContainer = MaterialTheme.colorScheme.surface

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(searchFieldContainer, searchFieldShape),
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_icon_desc)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = viewModel::onClear) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_icon_desc))
                    }
                }
            },
            singleLine = true,
            shape = searchFieldShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = searchFieldContainer,
                unfocusedContainerColor = searchFieldContainer,
                disabledContainerColor = searchFieldContainer,
            ),
        )

        AnimatedVisibility(
            visible = query.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                when {
                    isSearching -> SearchStatusRow(
                        text = stringResource(R.string.search_searching),
                        showSpinner = true,
                    )
                    searchFailed -> SearchStatusRow(
                        text = stringResource(R.string.search_error),
                        actionLabel = stringResource(R.string.search_retry),
                        onAction = viewModel::onRetry,
                    )
                    searchUnavailable -> SearchStatusRow(
                        text = stringResource(R.string.search_unavailable),
                        actionLabel = onManageMapPack?.let {
                            stringResource(R.string.search_manage_map_pack)
                        },
                        onAction = onManageMapPack,
                    )
                    results.isEmpty() -> SearchStatusRow(
                        text = stringResource(R.string.search_no_results, query),
                    )
                    else -> SearchResultsList(
                        results = results,
                        onResultSelected = { result ->
                            viewModel.onResultSelected(result)
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onResultSelected(result)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchStatusRow(
    text: String,
    showSpinner: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = searchResultSubtitle(result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<SearchResult>,
    onResultSelected: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedGroups = groupResultsByKind(results)

    LazyColumn(modifier = modifier.padding(8.dp)) {
        sortedGroups.forEach { (kind, groupResults) ->
            item(key = "header-$kind") {
                Text(
                    text = SearchPresentation.groupLabel(kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            itemsIndexed(
                groupResults,
                key = { index, result ->
                    "${result.kind}-${result.className}-${result.name}-" +
                        "${result.latitude}-${result.longitude}-$index"
                },
            ) { _, result ->
                SearchResultItem(
                    result = result,
                    onClick = { onResultSelected(result) },
                )
            }
        }
    }
}

internal val KIND_ORDER = listOf("suburb", "road", "poi", "water", "park")

internal fun searchResultSubtitle(result: SearchResult): String = buildString {
    append(
        SearchPresentation.classLabel(result.className)
            ?: SearchPresentation.kindLabel(result.kind),
    )
    append(" \u00b7 ")
    append(formatCoordinate(result.latitude, positive = "N", negative = "S"))
    append(", ")
    append(formatCoordinate(result.longitude, positive = "E", negative = "W"))
}

private fun formatCoordinate(value: Double, positive: String, negative: String): String =
    String.format(
        Locale.ROOT,
        "%.4f\u00b0 %s",
        abs(value),
        if (value >= 0.0) positive else negative,
    )

internal fun groupResultsByKind(results: List<SearchResult>): List<Pair<String, List<SearchResult>>> {
    val grouped = results.groupBy { it.kind }
    return grouped.entries.sortedBy { (kind, _) ->
        KIND_ORDER.indexOf(kind).takeIf { it >= 0 } ?: KIND_ORDER.size
    }.map { it.toPair() }
}

@Preview(showBackground = true)
@Composable
private fun SearchResultItemPreview() {
    au.com.ausroads.ui.designsystem.AusRoadsTheme {
        SearchResultItem(
            result = SearchResult(
                name = "Adelaide CBD",
                kind = "suburb",
                className = "city",
                latitude = -34.9285,
                longitude = 138.6007,
            ),
            onClick = {},
        )
    }
}
