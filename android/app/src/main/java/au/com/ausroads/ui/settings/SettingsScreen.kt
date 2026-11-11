/*
 * Settings screen (v0.1.1).
 * Wired to SettingsViewModel via Hilt. Theme mode, attribution toggle, map pack status,
 * and About navigation.
 */
@file:Suppress("LargeClass")

package au.com.ausroads.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import au.com.ausroads.BuildConfig
import au.com.ausroads.R
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.ThemeMode
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    mapPackViewModel: MapPackViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val writeError by viewModel.writeError.collectAsState()

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
            )

            // Theme mode
            Section(title = stringResource(R.string.settings_theme)) {
                ThemeModeSelector(
                    selected = settings.theme,
                    onSelect = viewModel::setThemeMode,
                )
            }

            HorizontalDivider()

            // Traffic overlay
            Section(title = stringResource(R.string.settings_traffic_overlay)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = settings.liveTrafficEnabled,
                            role = Role.Switch,
                            onValueChange = viewModel::setTrafficOverlayEnabled,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_traffic_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.liveTrafficEnabled,
                        onCheckedChange = null,
                    )
                }
            }

            HorizontalDivider()

            // TTS toggle
            Section(title = stringResource(R.string.settings_tts)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = settings.ttsEnabled,
                            role = Role.Switch,
                            onValueChange = viewModel::setTtsEnabled,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_tts_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.ttsEnabled,
                        onCheckedChange = null,
                    )
                }
            }

            HorizontalDivider()

            // Traffic API keys
            Section(title = stringResource(R.string.settings_api_keys)) {
                Text(
                    text = stringResource(R.string.settings_api_keys_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                ApiKeyField(
                    label = stringResource(R.string.settings_nsw_api_key),
                    value = settings.nswTrafficApiKey,
                    onValueChange = viewModel::setNswTrafficApiKey,
                    placeholder = stringResource(R.string.settings_nsw_api_key_placeholder),
                )
                ApiKeyField(
                    label = stringResource(R.string.settings_vic_api_key),
                    value = settings.vicTrafficApiKey,
                    onValueChange = viewModel::setVicTrafficApiKey,
                    placeholder = stringResource(R.string.settings_vic_api_key_placeholder),
                )
            }

            HorizontalDivider()

            // Attribution toggle
            Section(title = stringResource(R.string.settings_show_attribution)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = settings.showAttributionOverlay,
                            role = Role.Switch,
                            onValueChange = viewModel::setShowAttribution,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.about_osm_attribution),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.showAttributionOverlay,
                        onCheckedChange = null,
                    )
                }
            }

            HorizontalDivider()

            // Map pack status
            Section(title = stringResource(R.string.settings_map_pack)) {
                if (BuildConfig.CAN_DOWNLOAD_PACKS) {
                    val installedPack by mapPackViewModel.installed.collectAsState()
                    val downloadProgress by mapPackViewModel.inFlight.collectAsState()
                    val packUiState by mapPackViewModel.uiState.collectAsState()
                    val workerError by mapPackViewModel.downloadError.collectAsState()

                    MapPackSection(
                        installedPack = installedPack,
                        downloadProgress = downloadProgress,
                        isChecking = packUiState.isChecking,
                        // Worker diagnostics are retained in logs/state, but never
                        // interpolate internal exception text into the UI.
                        error = packUiState.error ?: workerError?.let {
                            stringResource(R.string.download_failed_generic)
                        },
                        onDownloadClick = mapPackViewModel::onDownloadClick,
                        onCancelClick = mapPackViewModel::onCancelClick,
                    )
                } else {
                    // The offline (privacy flagship) flavor cannot reach the network;
                    // don't offer a download button that would silently fail.
                    Text(
                        text = stringResource(R.string.settings_map_pack_download_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            // About
            Section(title = stringResource(R.string.settings_about)) {
                Text(
                    text = stringResource(R.string.about_privacy_posture),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onOpenAbout,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_view_attribution))
                }
            }
        }

        writeError?.let { error ->
            SettingsWriteErrorSnackbar(
                error = error,
                onRetry = viewModel::retryFailedWrite,
                onDismiss = viewModel::dismissWriteError,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
internal fun SettingsWriteErrorSnackbar(
    error: SettingsWriteError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settingName = when (error.setting) {
        SettingsField.Theme -> stringResource(R.string.settings_theme)
        SettingsField.LiveTraffic -> stringResource(R.string.settings_traffic_overlay)
        SettingsField.Attribution -> stringResource(R.string.settings_show_attribution)
        SettingsField.TextToSpeech -> stringResource(R.string.settings_tts)
        SettingsField.Congestion -> stringResource(R.string.settings_congestion_overlay)
        SettingsField.NswTrafficApiKey -> stringResource(R.string.settings_nsw_api_key)
        SettingsField.VicTrafficApiKey -> stringResource(R.string.settings_vic_api_key)
        SettingsField.RoutePreferences -> stringResource(R.string.settings_route_preferences)
    }

    Snackbar(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        action = {
            TextButton(
                onClick = onRetry,
                enabled = !error.isRetrying,
            ) {
                Text(
                    stringResource(
                        if (error.isRetrying) R.string.settings_retrying else R.string.action_retry,
                    ),
                )
            }
        },
        dismissAction = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        },
    ) {
        Text(stringResource(R.string.settings_save_failed, settingName))
    }
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.System to stringResource(R.string.settings_theme_system),
        ThemeMode.Light to stringResource(R.string.settings_theme_light),
        ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
    )
    Column {
        options.forEach { (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == mode,
                        onClick = { onSelect(mode) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == mode,
                    onClick = null, // handled by selectable
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable(label) { mutableStateOf(value) }
    var lastExternalValue by rememberSaveable(label) { mutableStateOf(value) }
    var lastSubmittedValue by rememberSaveable(label) { mutableStateOf(value) }
    var wasFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(value) {
        val canAdoptExternalValue = !wasFocused && draft == lastExternalValue
        if (canAdoptExternalValue) draft = value
        if (canAdoptExternalValue || value == draft) lastSubmittedValue = value
        lastExternalValue = value
    }
    val commit = {
        if (draft != lastSubmittedValue) {
            lastSubmittedValue = draft
            onValueChange(draft)
        }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                if (wasFocused && !focusState.isFocused) commit()
                wasFocused = focusState.isFocused
            },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                commit()
                focusManager.clearFocus()
            },
        ),
    )
}

@Suppress("LongParameterList")
@Composable
private fun MapPackSection(
    installedPack: InstalledPack?,
    downloadProgress: DownloadProgress?,
    isChecking: Boolean,
    error: String?,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (downloadProgress != null) {
            Text(
                text = when (downloadProgress.phase) {
                    DownloadProgress.Phase.FETCHING_MANIFEST -> stringResource(R.string.download_checking)
                    DownloadProgress.Phase.DOWNLOADING -> {
                        val downloaded = formatBytes(downloadProgress.bytesDownloaded)
                        val total = downloadProgress.totalBytes?.let { formatBytes(it) } ?: "?"
                        stringResource(R.string.download_downloading, downloaded, total)
                    }
                    DownloadProgress.Phase.VERIFYING -> stringResource(R.string.download_verifying)
                    DownloadProgress.Phase.EXTRACTING -> stringResource(R.string.download_extracting)
                    DownloadProgress.Phase.INSTALLING -> stringResource(R.string.download_installing)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val totalBytes = downloadProgress.totalBytes
            if (totalBytes != null && totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { downloadProgress.bytesDownloaded.toFloat() / totalBytes.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onCancelClick) {
                Text(stringResource(R.string.cancel))
            }
        } else if (installedPack != null && installedPack.version.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_map_pack_installed),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.download_version_info,
                    installedPack.version,
                    formatBytes(installedPack.totalSizeBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onDownloadClick, enabled = !isChecking) {
                Text(stringResource(R.string.settings_redownload))
            }
        } else {
            Text(
                text = stringResource(R.string.settings_map_pack_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onDownloadClick, enabled = !isChecking) {
                if (isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.download_map_pack))
            }
        }

        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024

private fun formatBytes(bytes: Long): String = when {
    bytes < KB -> "$bytes B"
    bytes < MB -> "${bytes / KB} KB"
    bytes < GB -> "%.1f MB".format(bytes / MB.toDouble())
    else -> "%.1f GB".format(bytes / GB.toDouble())
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    au.com.ausroads.ui.designsystem.AusRoadsTheme {
        SettingsScreen(onOpenAbout = {})
    }
}
