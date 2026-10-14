@file:Suppress("LongMethod", "TooManyFunctions", "LongParameterList")

package au.com.ausroads.ui.explore

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import au.com.ausroads.R
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.offline.search.DiscoveryAvailability
import au.com.ausroads.offline.search.ExploreCategory
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.placeKey
import au.com.ausroads.ui.nearby.NearbyScreen
import java.util.Locale

/** The established nearby route gains discovery, with essentials always one visible tab away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    reference: GeoPoint,
    onShowOnMap: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExploreViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var essentials by rememberSaveable { mutableStateOf(false) }
    var interests by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(reference) { viewModel.setReference(reference) }
    LaunchedEffect(state.visitNotice?.id) {
        val notice = state.visitNotice ?: return@LaunchedEffect
        try {
            val response =
                snackbar.showSnackbar(
                    message =
                        context.getString(
                            if (notice.marked) R.string.explore_visit_saved
                            else R.string.explore_visit_removed
                        ),
                    actionLabel = context.getString(R.string.action_undo),
                )
            if (response == SnackbarResult.ActionPerformed)
                viewModel.setVisited(notice.result, !notice.marked)
        } finally {
            viewModel.consumeVisitNotice(notice.id)
        }
    }
    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_explore)) },
                    actions = {
                        IconButton(onClick = { interests = true }) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = stringResource(R.string.explore_preferences),
                            )
                        }
                    },
                )
                TabRow(selectedTabIndex = if (essentials) 1 else 0) {
                    Tab(
                        selected = !essentials,
                        onClick = { essentials = false },
                        text = { Text(stringResource(R.string.explore_discover)) },
                    )
                    Tab(
                        selected = essentials,
                        onClick = { essentials = true },
                        text = { Text(stringResource(R.string.explore_essentials)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (essentials) {
            NearbyScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                mapReference = reference,
                onShowOnMap = onShowOnMap,
                showTitle = false,
            )
        } else {
            DiscoveryBody(
                state, viewModel, onShowOnMap, Modifier.fillMaxSize().padding(padding),
                onOpenInterests = { interests = true },
                preferencesInSheet = interests,
            )
        }
    }
    if (interests) {
        InterestsSheet(state, viewModel, onDismiss = { interests = false })
    }
}

@Composable
private fun DiscoveryBody(
    state: ExploreUiState,
    viewModel: ExploreViewModel,
    onShowOnMap: (SearchResult) -> Unit,
    modifier: Modifier,
    onOpenInterests: () -> Unit,
    preferencesInSheet: Boolean,
) {
    val loadingLabel = stringResource(
        if (state.preferences == null) R.string.explore_preferences_loading else R.string.explore_loading
    )
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.explore_offline_heading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = {
                    Text(
                        stringResource(R.string.explore_search_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.explore_search_description),
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty())
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.explore_clear_search),
                            )
                        }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.category == null && state.query.isBlank(),
                    onClick = { viewModel.setCategory(null) },
                    label = { Text(stringResource(R.string.explore_all)) },
                )
                ExploreCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { viewModel.setCategory(category) },
                        label = { Text(stringResource(category.labelRes())) },
                    )
                }
            }
            Text(
                stringResource(R.string.explore_categories_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExploreViewModel.RADII.forEach { radius ->
                    FilterChip(
                        selected = radius == state.radiusKm,
                        onClick = { viewModel.setRadius(radius) },
                        label = { Text(stringResource(R.string.explore_radius, radius)) },
                    )
                }
            }
            Text(
                stringResource(R.string.explore_area),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isSaving && !preferencesInSheet)
            item { SavingNotice() }
        if (state.visitLimitReached && !preferencesInSheet)
            item {
                ErrorCard(
                    R.string.explore_history_full,
                    R.string.explore_preferences,
                    onOpenInterests,
                )
            }
        if (state.preferencesWriteFailed && !preferencesInSheet)
            item {
                ErrorCard(
                    R.string.explore_write_error,
                    R.string.explore_write_retry,
                    viewModel::retryWrite,
                    enabled = !state.isSaving,
                )
            }
        when {
            state.preferencesReadFailed ->
                item {
                    if (!preferencesInSheet)
                        ErrorCard(
                            R.string.explore_preferences_error,
                            R.string.explore_retry,
                            viewModel::retry,
                        )
                }
            state.catalog == DiscoveryAvailability.FAILED ||
                state.catalog == DiscoveryAvailability.UNAVAILABLE ->
                item {
                    ErrorCard(
                        R.string.explore_catalog_error,
                        R.string.explore_retry,
                        viewModel::retry,
                    )
                }
            state.queryFailed ->
                item {
                    ErrorCard(
                        R.string.explore_query_error,
                        R.string.explore_retry,
                        viewModel::retry,
                    )
                }
            state.isLoading ->
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text(loadingLabel, Modifier.padding(start = 8.dp))
                    }
                }
            state.results.isEmpty() ->
                item {
                    Text(
                        stringResource(R.string.explore_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.radiusKm < 50)
                        TextButton(onClick = { viewModel.setRadius(50) }) {
                            Text(stringResource(R.string.explore_expand))
                        }
                }
            else -> {
                item {
                    Text(
                        stringResource(
                            if (state.query.isBlank()) R.string.explore_suggestions
                            else R.string.explore_matches
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(state.results, key = { it.result.placeKey() }) { place ->
                    PlaceCard(
                        place,
                        enabled = state.preferences != null && !state.preferencesReadFailed && !state.isSaving,
                        onMap = { onShowOnMap(place.result) },
                        onVisited = { viewModel.setVisited(place.result, !place.visited) },
                    )
                }
            }
        }
        item {
            Text(
                stringResource(
                    if (state.usingBundledCatalog) R.string.explore_catalog_bundled
                    else R.string.explore_catalog_installed
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.explore_catalog_limits),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaceCard(
    place: SuggestedPlace,
    enabled: Boolean,
    onMap: () -> Unit,
    onVisited: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                place.result.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${stringResource(place.category.labelRes())} · ${formatPlaceDistance(place.distanceMeters)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                placeReason(place),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMap) {
                    Icon(
                        Icons.Default.Explore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.explore_on_map), Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onVisited, enabled = enabled) {
                    Text(
                        stringResource(
                            if (place.visited) R.string.explore_undo_visit
                            else R.string.explore_mark_visited
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun placeReason(place: SuggestedPlace): String =
    when (place.reason) {
        PlaceReason.INTEREST ->
            stringResource(
                R.string.explore_reason_interest,
                stringResource(place.category.labelRes()),
            )
        PlaceReason.PREVIOUS_VISITS ->
            stringResource(
                R.string.explore_reason_visits,
                stringResource(place.category.labelRes()),
            )
        PlaceReason.NEW_NEARBY -> stringResource(R.string.explore_reason_new)
        PlaceReason.NEARBY -> stringResource(R.string.explore_reason_nearby)
        PlaceReason.KEYWORD -> stringResource(R.string.explore_reason_keyword)
        PlaceReason.VISITED -> stringResource(R.string.explore_reason_visited)
    }

@Composable
private fun ErrorCard(message: Int, action: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(message), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onClick, enabled = enabled) { Text(stringResource(action)) }
        }
    }
}

@Composable
private fun SavingNotice() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Text(stringResource(R.string.explore_saving), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterestsSheet(
    state: ExploreUiState,
    viewModel: ExploreViewModel,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmClear by remember { mutableStateOf(false) }
    val preferences = state.preferences?.takeUnless { state.preferencesReadFailed }
    val enabled = preferences != null && !state.isSaving
    val canClear = !state.isSaving && (enabled || state.preferencesReadFailed)
    val newFirstLabel = stringResource(R.string.explore_new_first)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.explore_preferences),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.explore_close_interests)) }
        }
        Column(
            Modifier.fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.explore_preferences_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.preferencesReadFailed)
                ErrorCard(
                    R.string.explore_preferences_error,
                    R.string.explore_retry,
                    viewModel::retry,
                )
            if (state.isSaving) SavingNotice()
            if (state.visitLimitReached)
                Text(
                    stringResource(R.string.explore_history_full),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            if (state.preferencesWriteFailed)
                ErrorCard(
                    R.string.explore_write_error,
                    R.string.explore_write_retry,
                    viewModel::retryWrite,
                    enabled = !state.isSaving,
                )
            if (preferences == null) {
                Text(
                    stringResource(
                        if (state.preferencesReadFailed) R.string.explore_preferences_unknown
                        else R.string.explore_preferences_loading
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                ExploreCategory.entries.toList().chunked(2).forEach { categories ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        categories.forEach { category ->
                            FilterChip(
                                selected = category in preferences.interests,
                                enabled = enabled,
                                onClick = {
                                    viewModel.setInterest(category, category !in preferences.interests)
                                },
                                label = { Text(stringResource(category.labelRes())) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(newFirstLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.explore_new_first_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = preferences.preferNew,
                        onCheckedChange = viewModel::setPreferNew,
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = newFirstLabel },
                    )
                }
                val count = preferences.visits.size
                Text(
                    if (count == 1) stringResource(R.string.explore_visit_count_one)
                    else stringResource(R.string.explore_visit_count_many, count),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if ((preferences?.visits?.size ?: 0) > 0 || state.preferencesReadFailed)
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = canClear,
                ) {
                    Text(stringResource(R.string.explore_clear_visits))
                }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmClear)
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.explore_clear_confirm_title)) },
            text = { Text(stringResource(R.string.explore_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearVisits()
                        confirmClear = false
                    },
                    enabled = canClear,
                ) {
                    Text(stringResource(R.string.explore_clear_visits))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.explore_cancel))
                }
            },
        )
}

fun ExploreCategory.labelRes(): Int =
    when (this) {
        ExploreCategory.COFFEE -> R.string.explore_coffee
        ExploreCategory.FOOD -> R.string.explore_food
        ExploreCategory.NATURE -> R.string.explore_nature
        ExploreCategory.VIEWS -> R.string.explore_views
        ExploreCategory.CULTURE -> R.string.explore_culture
        ExploreCategory.PICNIC -> R.string.explore_picnic
        ExploreCategory.PLAY -> R.string.explore_play
        ExploreCategory.CAMPING -> R.string.explore_camping
    }

private fun formatPlaceDistance(meters: Double): String =
    if (meters < 1_000.0) String.format(Locale.getDefault(), "%.0f m", meters)
    else String.format(Locale.getDefault(), "%.1f km", meters / 1_000.0)
