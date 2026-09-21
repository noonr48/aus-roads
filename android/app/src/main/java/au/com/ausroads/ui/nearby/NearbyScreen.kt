/*
 * NearbyScreen — the "Nearby" tab.
 *
 * Material 3 screen: it shows
 * the reference coordinate in several notations, a always-visible emergency
 * summary (nearest hospital + police), a horizontally scrollable row of
 * category filter chips, and a distance-sorted result list for the selected
 * category. Nearby results return to the app's own map with a selected marker;
 * coordinate/emergency sharing remains an explicit external action.
 */
@file:Suppress("LongMethod", "TooManyFunctions")

package au.com.ausroads.ui.nearby

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.location.Location
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import au.com.ausroads.R
import au.com.ausroads.core.geo.CoordinateFormatter
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.offline.search.PoiCategory
import au.com.ausroads.offline.search.SearchResult
import au.com.ausroads.offline.search.SearchPresentation
import au.com.ausroads.ui.map.isLocationFixFresh
import au.com.ausroads.ui.map.maximumLocationFixAgeMillis
import au.com.ausroads.ui.map.rememberForegroundLocationPermissionState
import au.com.ausroads.ui.map.rememberUserLocation
import java.util.Locale
import kotlinx.coroutines.launch

/** Friendly label string-resource for each [PoiCategory]. */
private fun PoiCategory.labelRes(): Int = when (this) {
    PoiCategory.FUEL -> R.string.nearby_cat_fuel
    PoiCategory.EV_CHARGING -> R.string.nearby_cat_ev
    PoiCategory.HOSPITAL -> R.string.nearby_cat_hospital
    PoiCategory.PHARMACY -> R.string.nearby_cat_pharmacy
    PoiCategory.POLICE -> R.string.nearby_cat_police
    PoiCategory.FIRE_STATION -> R.string.nearby_cat_fire
    PoiCategory.TOILETS -> R.string.nearby_cat_toilets
    PoiCategory.DRINKING_WATER -> R.string.nearby_cat_water
    PoiCategory.CAMPING -> R.string.nearby_cat_camping
    PoiCategory.SUPERMARKET -> R.string.nearby_cat_supermarket
}

/**
 * Icon for each [PoiCategory]. Every vector here is verified to exist:
 * ShoppingCart/Place/Share are in material-icons-core; the rest are in
 * material-icons-extended, which the :app compose convention puts on the
 * classpath.
 */
private fun PoiCategory.icon(): ImageVector = when (this) {
    PoiCategory.FUEL -> Icons.Filled.LocalGasStation
    PoiCategory.EV_CHARGING -> Icons.Filled.EvStation
    PoiCategory.HOSPITAL -> Icons.Filled.LocalHospital
    PoiCategory.PHARMACY -> Icons.Filled.LocalPharmacy
    PoiCategory.POLICE -> Icons.Filled.LocalPolice
    PoiCategory.FIRE_STATION -> Icons.Filled.LocalFireDepartment
    PoiCategory.TOILETS -> Icons.Filled.Wc
    PoiCategory.DRINKING_WATER -> Icons.Filled.WaterDrop
    PoiCategory.CAMPING -> Icons.Filled.Cabin
    PoiCategory.SUPERMARKET -> Icons.Filled.ShoppingCart
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    modifier: Modifier = Modifier,
    viewModel: NearbyViewModel = hiltViewModel(),
    onViewOnMap: (SearchResult) -> Unit = {},
    onManageMapPack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val locationDeniedMessage = stringResource(R.string.nearby_location_permission_denied)
    val shareFallbackMessage = stringResource(R.string.nearby_share_copied_fallback)
    val locationPermission = rememberForegroundLocationPermissionState(
        context = context,
        onDenied = {
            coroutineScope.launch { snackbarHostState.showSnackbar(locationDeniedMessage) }
        },
    )
    val userLocation = rememberUserLocation(locationPermission.isGranted)
    var appliedLocation by remember { mutableStateOf<Location?>(null) }
    var minimumFixTimeMillis by remember { mutableStateOf(0L) }
    var useAdelaideFallback by remember { mutableStateOf(false) }

    LaunchedEffect(
        locationPermission.isGranted,
        userLocation.value,
        appliedLocation,
        minimumFixTimeMillis,
        locationPermission.isPreciseGranted,
        useAdelaideFallback,
    ) {
        if (useAdelaideFallback) {
            appliedLocation = null
            minimumFixTimeMillis = 0L
            viewModel.resetReference()
            return@LaunchedEffect
        }
        if (!locationPermission.isGranted) {
            appliedLocation = null
            minimumFixTimeMillis = 0L
            viewModel.resetReference()
            return@LaunchedEffect
        }
        val fix = userLocation.value
        val now = System.currentTimeMillis()
        val previous = appliedLocation
        val freshFix = fix?.takeIf {
            isFreshNearbyFix(
                fixTimeMillis = it.time,
                nowMillis = now,
                minimumFixTimeMillis = minimumFixTimeMillis,
                isPreciseLocationGranted = locationPermission.isPreciseGranted,
            )
        }
        if (freshFix == null) {
            appliedLocation = null
            viewModel.resetReference()
            return@LaunchedEffect
        }
        val shouldApply = shouldApplyNearbyFix(
            previous = previous,
            next = freshFix,
        )
        if (shouldApply) {
            viewModel.setReference(latitude = freshFix.latitude, longitude = freshFix.longitude)
            appliedLocation = Location(freshFix)
        }
    }

    val shareAt: (Double, Double, String?) -> Unit = { lat, lon, label ->
        val shareText = locationShareText(lat, lon, label)
        if (!shareLocation(context, shareText)) {
            copyLocation(context, shareText)
            coroutineScope.launch { snackbarHostState.showSnackbar(shareFallbackMessage) }
        }
    }
    val locationStatus = stringResource(
        when {
            appliedLocation != null -> R.string.nearby_using_device_location
            useAdelaideFallback -> R.string.nearby_using_adelaide_fallback
            locationPermission.foregroundNeedsSettings ->
                R.string.nearby_location_permission_settings
            locationPermission.isGranted -> R.string.nearby_waiting_for_location
            else -> R.string.nearby_using_adelaide_fallback
        },
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.nearby_title)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CategorySelector(
                categories = state.categories,
                selected = state.selected,
                locationStatus = locationStatus.takeIf { state.selected != null },
                onSelect = { viewModel.selectCategory(it) },
            )

            ResultsSection(
                state = state,
                onViewOnMap = onViewOnMap,
                onRetry = { state.selected?.let(viewModel::selectCategory) },
                onManageMapPack = onManageMapPack,
                onTryAdelaide = if (appliedLocation != null) {
                    {
                        useAdelaideFallback = true
                        appliedLocation = null
                        minimumFixTimeMillis = 0L
                        viewModel.resetReference()
                    }
                } else {
                    null
                },
            )

            CoordinatesCard(
                state = CoordinatesCardState(
                    reference = viewModel.reference,
                    locationStatus = locationStatus,
                    isLocating = locationPermission.isGranted && appliedLocation == null,
                    useMyLocationLabel = stringResource(
                        if (appliedLocation == null) {
                            if (locationPermission.foregroundNeedsSettings) {
                                R.string.nearby_open_location_settings
                            } else {
                                R.string.nearby_use_my_location
                            }
                        } else {
                            R.string.nearby_refresh_location
                        },
                    ),
                ),
                onShare = { shareAt(viewModel.reference.latitude, viewModel.reference.longitude, null) },
                onUseMyLocation = {
                    useAdelaideFallback = false
                    if (locationPermission.isGranted) {
                        appliedLocation = null
                        minimumFixTimeMillis = System.currentTimeMillis()
                    } else {
                        locationPermission.request()
                    }
                },
            )

            EmergencySection(
                state = state,
                onShare = shareAt,
                onRetry = viewModel::loadEmergency,
                onManageMapPack = onManageMapPack,
            )
        }
    }
}

@Composable
private fun CoordinatesCard(
    state: CoordinatesCardState,
    onShare: () -> Unit,
    onUseMyLocation: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.nearby_coordinates),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isLocating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = state.locationStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            CoordinateRow(
                label = stringResource(R.string.nearby_coordinate_decimal),
                value = CoordinateFormatter.decimalDegrees(state.reference),
            )
            CoordinateRow(
                label = stringResource(R.string.nearby_coordinate_dms),
                value = CoordinateFormatter.dms(state.reference),
            )
            CoordinateRow(
                label = stringResource(R.string.nearby_coordinate_mgrs),
                value = CoordinateFormatter.mgrs(state.reference),
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.nearby_share_location),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = onUseMyLocation) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = state.useMyLocationLabel,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

private data class CoordinatesCardState(
    val reference: GeoPoint,
    val locationStatus: String,
    val isLocating: Boolean,
    val useMyLocationLabel: String,
)

@Composable
private fun CoordinateRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmergencySection(
    state: NearbyUiState,
    onShare: (Double, Double, String?) -> Unit,
    onRetry: () -> Unit,
    onManageMapPack: () -> Unit,
) {
    val hospital = state.emergency?.hospital
    val police = state.emergency?.police
    val hasAnyResult = hospital != null || police != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.nearby_emergency_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (state.isEmergencyLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.nearby_emergency_loading),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            hospital?.let {
                EmergencyRow(
                    label = stringResource(R.string.nearby_nearest_hospital),
                    item = it,
                    onShare = onShare,
                )
            }
            police?.let {
                EmergencyRow(
                    label = stringResource(R.string.nearby_nearest_police),
                    item = it,
                    onShare = onShare,
                )
            }
            if (state.searchUnavailable) {
                Text(
                    text = stringResource(R.string.nearby_emergency_preparing),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = onManageMapPack) {
                    Text(stringResource(R.string.map_manage_pack))
                }
            }
            if (!state.searchUnavailable && !state.isEmergencyLoading) {
                if (!hasAnyResult && !state.emergencyError) {
                    Text(
                        text = stringResource(R.string.nearby_emergency_empty),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (!state.isEmergencyLoading && state.emergencyError) {
                Text(
                    text = stringResource(R.string.nearby_emergency_failed),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
private fun EmergencyRow(
    label: String,
    item: NearbyResult,
    onShare: (Double, Double, String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = item.result.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = formatDistance(item.distanceMeters),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        IconButton(
            onClick = { onShare(item.result.latitude, item.result.longitude, item.result.name) },
        ) {
            Icon(
                Icons.Filled.Share,
                contentDescription = stringResource(
                    R.string.nearby_share_named_location,
                    item.result.name,
                ),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySelector(
    categories: List<PoiCategory>,
    selected: PoiCategory?,
    locationStatus: String?,
    onSelect: (PoiCategory) -> Unit,
) {
    val categoryListState = rememberLazyListState()

    LaunchedEffect(categories, selected) {
        val selectedIndex = categories.indexOf(selected)
        if (selectedIndex >= 0) categoryListState.animateScrollToItem(selectedIndex)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.nearby_categories_title),
            style = MaterialTheme.typography.titleMedium,
        )
        locationStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            state = categoryListState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            items(
                items = categories,
                key = PoiCategory::name,
            ) { category ->
                FilterChip(
                    selected = category == selected,
                    onClick = { onSelect(category) },
                    label = { Text(stringResource(category.labelRes())) },
                    leadingIcon = {
                        Icon(
                            imageVector = category.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultsSection(
    state: NearbyUiState,
    onViewOnMap: (SearchResult) -> Unit,
    onRetry: () -> Unit,
    onManageMapPack: () -> Unit,
    onTryAdelaide: (() -> Unit)?,
) {
    when {
        state.searchUnavailable -> {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.nearby_search_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onManageMapPack) {
                    Text(stringResource(R.string.map_manage_pack))
                }
            }
        }

        state.isLoading -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        state.categoryError -> {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.nearby_query_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }

        state.selected != null && state.results.isEmpty() -> {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        R.string.nearby_no_results,
                        stringResource(state.selected.labelRes()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                onTryAdelaide?.let { action ->
                    TextButton(onClick = action) {
                        Text(stringResource(R.string.nearby_try_adelaide))
                    }
                }
            }
        }

        state.results.isNotEmpty() -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.nearby_results_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.results.forEach { item ->
                    ResultRow(item = item, onViewOnMap = onViewOnMap)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    item: NearbyResult,
    onViewOnMap: (SearchResult) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.result.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                SearchPresentation.classLabel(item.result.className)?.let { category ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatDistance(item.distanceMeters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onViewOnMap(item.result) },
            ) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = stringResource(
                        R.string.nearby_view_named_on_map,
                        item.result.name,
                    ),
                )
            }
        }
    }
}

/** "%.0f m" under a kilometre, else "%.1f km". */
private fun formatDistance(meters: Double): String =
    if (meters < METERS_PER_KM) {
        String.format(Locale.US, "%.0f m", meters)
    } else {
        String.format(Locale.US, "%.1f km", meters / METERS_PER_KM)
    }

/**
 * Share prepared coordinate text through ordinary messaging/email targets.
 * Returns false so the caller can preserve the value through a clipboard fallback.
 */
private fun shareLocation(
    context: android.content.Context,
    shareText: String,
): Boolean {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, shareText)
    val chooser = Intent.createChooser(intent, context.getString(R.string.nearby_share_location))
    return runCatching { context.startActivity(chooser) }.isSuccess
}

private fun copyLocation(context: android.content.Context, shareText: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.nearby_clipboard_label), shareText),
    )
}

internal fun locationShareText(lat: Double, lon: Double, label: String?): String {
    val coords = String.format(Locale.US, "%.6f,%.6f", lat, lon)
    val mapUrl = String.format(
        Locale.US,
        "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f",
        lat,
        lon,
        lat,
        lon,
    )
    return listOfNotNull(label?.takeIf(String::isNotBlank), coords, mapUrl).joinToString("\n")
}

internal fun isFreshNearbyFix(
    fixTimeMillis: Long,
    nowMillis: Long,
    minimumFixTimeMillis: Long,
    isPreciseLocationGranted: Boolean,
): Boolean {
    // Android may deliver approximate fixes only every several minutes. A
    // manual refresh cannot force that cadence, so keep the current coarse fix
    // instead of silently falling back to Adelaide until another callback.
    val effectiveMinimumFixTimeMillis =
        if (isPreciseLocationGranted) minimumFixTimeMillis else 0L
    return isLocationFixFresh(
        fixTimeMillis = fixTimeMillis,
        nowMillis = nowMillis,
        minimumFixTimeMillis = effectiveMinimumFixTimeMillis,
        maxAgeMillis = maximumLocationFixAgeMillis(isPreciseLocationGranted),
    )
}

private fun shouldApplyNearbyFix(previous: Location?, next: Location?): Boolean {
    if (next == null) return false
    if (previous == null) return true
    val movedMeters = previous.distanceTo(next)
    val elapsedMillis = next.time - previous.time
    val accuracyImproved = next.hasAccuracy() && previous.hasAccuracy() &&
        next.accuracy + MEANINGFUL_ACCURACY_GAIN_METERS < previous.accuracy
    return movedMeters >= MIN_REFERENCE_MOVE_METERS ||
        elapsedMillis >= REFERENCE_REFRESH_INTERVAL_MS ||
        accuracyImproved
}

private const val METERS_PER_KM = 1000.0
private const val REFERENCE_REFRESH_INTERVAL_MS = 60_000L
private const val MIN_REFERENCE_MOVE_METERS = 100f
private const val MEANINGFUL_ACCURACY_GAIN_METERS = 50f
