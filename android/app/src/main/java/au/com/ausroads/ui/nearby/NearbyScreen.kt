/*
 * NearbyScreen — the "Nearby" tab.
 *
 * Self-contained Material 3 screen (no external navigation callbacks): it shows
 * the reference coordinate in several notations, a always-visible emergency
 * summary (nearest hospital + police), a horizontally scrollable row of
 * category filter chips, and a distance-sorted result list for the selected
 * category. Sharing / "view on map" is done with a geo: intent fired through
 * the LocalContext, so the screen needs nothing wired in from above.
 */
@file:Suppress("LongMethod", "TooManyFunctions")

package au.com.ausroads.ui.nearby

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import au.com.ausroads.R
import au.com.ausroads.core.geo.CoordinateFormatter
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.offline.search.PoiCategory
import java.util.Locale

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
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val shareAt: (Double, Double, String?) -> Unit = { lat, lon, label ->
        shareLocation(context, lat, lon, label)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.nearby_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CoordinatesCard(
                reference = viewModel.reference,
                onShare = { shareAt(viewModel.reference.latitude, viewModel.reference.longitude, null) },
            )

            EmergencySection(
                emergency = state.emergency,
                onShare = shareAt,
            )

            CategorySelector(
                categories = state.categories,
                selected = state.selected,
                onSelect = { viewModel.selectCategory(it) },
            )

            ResultsSection(
                selected = state.selected,
                results = state.results,
                isLoading = state.isLoading,
                onShare = shareAt,
            )
        }
    }
}

@Composable
private fun CoordinatesCard(
    reference: GeoPoint,
    onShare: () -> Unit,
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
            Spacer(Modifier.height(4.dp))
            CoordinateRow(
                label = stringResource(R.string.nearby_coordinate_decimal),
                value = CoordinateFormatter.decimalDegrees(reference),
            )
            CoordinateRow(
                label = stringResource(R.string.nearby_coordinate_dms),
                value = CoordinateFormatter.dms(reference),
            )
            CoordinateRow(
                label = stringResource(R.string.nearby_coordinate_mgrs),
                value = CoordinateFormatter.mgrs(reference),
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.nearby_share_location),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

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
    emergency: EmergencyInfo?,
    onShare: (Double, Double, String?) -> Unit,
) {
    val hospital = emergency?.hospital
    val police = emergency?.police
    if (hospital == null && police == null) return

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
                contentDescription = stringResource(R.string.nearby_share_location),
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
    onSelect: (PoiCategory) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.nearby_categories_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { category ->
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
    selected: PoiCategory?,
    results: List<NearbyResult>,
    isLoading: Boolean,
    onShare: (Double, Double, String?) -> Unit,
) {
    when {
        isLoading -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        selected != null && results.isEmpty() -> {
            Text(
                text = stringResource(
                    R.string.nearby_no_results,
                    stringResource(selected.labelRes()),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        }

        results.isNotEmpty() -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.nearby_results_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                results.forEach { item ->
                    ResultRow(item = item, onShare = onShare)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    item: NearbyResult,
    onShare: (Double, Double, String?) -> Unit,
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
                item.result.className?.takeIf { it.isNotBlank() }?.let { cls ->
                    Text(
                        text = cls,
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
                onClick = { onShare(item.result.latitude, item.result.longitude, item.result.name) },
            ) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = stringResource(R.string.nearby_view_on_map),
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
 * Fire a geo: intent for [lat]/[lon] through a chooser. Best-effort: if no app
 * can handle it the call is silently ignored rather than crashing.
 */
private fun shareLocation(
    context: android.content.Context,
    lat: Double,
    lon: Double,
    label: String?,
) {
    val coords = String.format(Locale.US, "%.6f,%.6f", lat, lon)
    val query = if (label.isNullOrBlank()) coords else "$coords($label)"
    val geoUri = Uri.parse("geo:$coords?q=$query")
    val intent = Intent(Intent.ACTION_VIEW, geoUri)
    val chooser = Intent.createChooser(intent, context.getString(R.string.nearby_view_on_map))
    runCatching { context.startActivity(chooser) }
}

private const val METERS_PER_KM = 1000.0
