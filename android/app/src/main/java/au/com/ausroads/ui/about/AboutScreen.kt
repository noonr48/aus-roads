/*
 * About screen — the canonical attribution page.
 * Lists every third-party dataset, library, and license that ships in the APK.
 * Lives at au.com.ausroads.ui.about.AboutScreen.
 */
package au.com.ausroads.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import au.com.ausroads.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.about_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        AboutContent(
            padding = padding,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun AboutContent(padding: PaddingValues, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.about_section_app))
        BodyText(stringResource(R.string.about_app_license))

        SectionDivider()
        SectionTitle(stringResource(R.string.about_section_data))
        DataItem(
            name = stringResource(R.string.about_osm_name),
            license = stringResource(R.string.about_osm_license),
            note = stringResource(R.string.about_osm_note),
        )
        DataItem(
            name = stringResource(R.string.about_geofabrik_name),
            license = stringResource(R.string.about_geofabrik_license),
            note = stringResource(R.string.about_geofabrik_note),
        )
        DataItem(
            name = stringResource(R.string.about_openmaptiles_name),
            license = stringResource(R.string.about_openmaptiles_license),
            note = stringResource(R.string.about_openmaptiles_note),
        )
        BodyText(
            text = stringResource(R.string.about_odbl_notice),
            style = MaterialTheme.typography.bodySmall,
        )

        SectionDivider()
        SectionTitle(stringResource(R.string.about_section_traffic))
        DataItem(
            name = stringResource(R.string.about_traffic_sa_name),
            license = stringResource(R.string.about_traffic_sa_license),
            note = stringResource(R.string.about_traffic_sa_note),
        )
        DataItem(
            name = stringResource(R.string.about_dit_name),
            license = stringResource(R.string.about_dit_license),
            note = stringResource(R.string.about_dit_note),
        )

        SectionDivider()
        SectionTitle(stringResource(R.string.about_section_libraries))
        LibraryItem(
            name = stringResource(R.string.about_lib_maplibre_name),
            license = stringResource(R.string.about_lib_maplibre_license),
            note = stringResource(R.string.about_lib_maplibre_note),
        )
        LibraryItem(
            name = stringResource(R.string.about_lib_valhalla_name),
            license = stringResource(R.string.about_lib_valhalla_license),
            note = stringResource(R.string.about_lib_valhalla_note),
        )
        LibraryItem(
            name = stringResource(R.string.about_lib_compose_name),
            license = stringResource(R.string.about_lib_compose_license),
            note = stringResource(R.string.about_lib_compose_note),
        )
        LibraryItem(
            name = stringResource(R.string.about_lib_kotlin_name),
            license = stringResource(R.string.about_lib_kotlin_license),
            note = stringResource(R.string.about_lib_kotlin_note),
        )
        LibraryItem(
            name = stringResource(R.string.about_lib_ktor_name),
            license = stringResource(R.string.about_lib_ktor_license),
            note = stringResource(R.string.about_lib_ktor_note),
        )
        LibraryItem(
            name = stringResource(R.string.about_lib_serialization_name),
            license = stringResource(R.string.about_lib_serialization_license),
            note = stringResource(R.string.about_lib_serialization_note),
        )
        LibraryItem(
            name = stringResource(R.string.about_lib_room_name),
            license = stringResource(R.string.about_lib_room_license),
            note = stringResource(R.string.about_lib_room_note),
        )
        LibraryItem(
            name = stringResource(R.string.about_lib_datastore_name),
            license = stringResource(R.string.about_lib_datastore_license),
            note = stringResource(R.string.about_lib_datastore_note),
        )
        LibraryItem(
            name = stringResource(R.string.about_lib_hilt_name),
            license = stringResource(R.string.about_lib_hilt_license),
            note = stringResource(R.string.about_lib_hilt_note),
        )

        SectionDivider()
        SectionTitle(stringResource(R.string.about_section_privacy))
        BodyText(stringResource(R.string.about_privacy_posture))
        BodyText(
            text = stringResource(R.string.about_privacy_full),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun BodyText(text: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(text = text, style = style, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DataItem(name: String, license: String, note: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(text = name, style = MaterialTheme.typography.titleSmall)
        Text(
            text = license,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = note, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LibraryItem(name: String, license: String, note: String) {
    DataItem(name = name, license = license, note = note)
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    au.com.ausroads.ui.designsystem.AusRoadsTheme {
        AboutScreen(onBack = {})
    }
}
