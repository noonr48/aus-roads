package au.com.ausroads.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey

@Composable
fun SearchOverlay(
    viewModel: SearchViewModel,
    onResultSelected: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
    coverageHint: String? = null,
    mapReference: GeoPoint? = null,
    statusSlot: @Composable () -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchFailed by viewModel.searchFailed.collectAsState()

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search_icon_desc),
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = viewModel::onClear) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = stringResource(R.string.clear_icon_desc),
                                )
                            }
                        }
                    },
                    singleLine = true,
                )
                if (coverageHint != null) {
                    Text(
                        coverageHint,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                statusSlot()
            }
        }

        AnimatedVisibility(
            visible = query.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (searchFailed && !isSearching)
                                MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surface
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                when {
                    isSearching ->
                        SearchStatusRow(
                            text = stringResource(R.string.search_searching),
                            showSpinner = true,
                        )
                    searchFailed -> {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                stringResource(R.string.search_error_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.search_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = viewModel::retry, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.search_retry))
                            }
                        }
                    }
                    results.isEmpty() ->
                        SearchStatusRow(text = stringResource(R.string.search_no_results, query))
                    else ->
                        SearchResultsList(
                            results = results,
                            reference = mapReference,
                            onResultSelected = { result ->
                                viewModel.onResultSelected(result)
                                focus.clearFocus()
                                onResultSelected(result)
                            },
                            modifier = Modifier.fillMaxWidth(),
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
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
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
        )
    }
}

@Composable
private fun SearchResultItem(
    result: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    reference: GeoPoint? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text =
                    buildString {
                        append(result.kind.replaceFirstChar { it.uppercase() })
                        if (!result.className.isNullOrBlank()) {
                            append(" \u00b7 ")
                            append(result.className)
                        }
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            mapDistanceLabel(result, reference)?.let { distance ->
                Text(
                    stringResource(R.string.search_result_distance, distance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<SearchResult>,
    reference: GeoPoint?,
    onResultSelected: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedGroups = groupResultsByKind(results.distinctBy { it.placeKey() })

    LazyColumn(modifier = modifier.heightIn(max = 320.dp).padding(8.dp)) {
        sortedGroups.forEach { (kind, groupResults) ->
            item(key = "header-$kind") {
                Text(
                    text = kind.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            items(groupResults, key = { it.placeKey() }) { result ->
                SearchResultItem(
                    result = result,
                    reference = reference,
                    onClick = { onResultSelected(result) },
                )
            }
        }
    }
}

internal val KIND_ORDER = listOf("suburb", "road", "poi", "water", "park")

internal fun groupResultsByKind(
    results: List<SearchResult>
): List<Pair<String, List<SearchResult>>> {
    val grouped = results.groupBy { it.kind }
    return grouped.entries
        .sortedBy { (kind, _) ->
            KIND_ORDER.indexOf(kind).takeIf { it >= 0 } ?: KIND_ORDER.size
        }
        .map { it.toPair() }
}

@Preview(showBackground = true)
@Composable
private fun SearchResultItemPreview() {
    au.com.ausroads.ui.designsystem.AusRoadsTheme {
        SearchResultItem(
            result =
                SearchResult(
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
