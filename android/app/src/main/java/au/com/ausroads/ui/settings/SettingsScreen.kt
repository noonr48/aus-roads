/*
 * Settings screen (v0.1.1).
 * Wired to SettingsViewModel via Hilt. Theme mode, attribution toggle, map pack status,
 * and About navigation.
 */
@file:Suppress("LargeClass")

package au.com.ausroads.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import au.com.ausroads.BuildConfig
import au.com.ausroads.R
import au.com.ausroads.data.settings.ThemeMode
import au.com.ausroads.offline.download.state.DOWNLOAD_CANCEL_FAILED
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack

@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    mapPackViewModel: MapPackViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val store by viewModel.storeState.collectAsState()
    val nswDraft by viewModel.nswApiDraft.collectAsState()
    val vicDraft by viewModel.vicApiDraft.collectAsState()
    val canChange = store.canEdit && !store.isSaving

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        SettingsStoreNotice(state = store, onRetry = viewModel::retry)

        Section(title = stringResource(R.string.settings_offline_coverage)) {
            Text(
                text = stringResource(R.string.settings_offline_coverage_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(
                        if (BuildConfig.CAN_DOWNLOAD_PACKS) R.string.settings_offline_network_hint
                        else R.string.settings_offline_private_hint
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        // Theme mode
        Section(title = stringResource(R.string.settings_theme)) {
            ThemeModeSelector(
                selected = settings.theme.takeIf { store.canEdit },
                onSelect = viewModel::setThemeMode,
                enabled = canChange,
            )
        }

        HorizontalDivider()

        // Offline has no INTERNET permission or live provider: no dead controls.
        if (BuildConfig.CAN_DOWNLOAD_PACKS) {
            Section(title = stringResource(R.string.settings_traffic_overlay)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_traffic_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    BoundSettingsSwitch(
                        label = stringResource(R.string.settings_traffic_overlay),
                        checked = settings.liveTrafficEnabled.takeIf { store.canEdit },
                        onCheckedChange = viewModel::setTrafficOverlayEnabled,
                        enabled = canChange,
                    )
                }
            }
            HorizontalDivider()
        }

        // Speed cameras
        Section(title = stringResource(R.string.settings_speed_cameras)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_speed_cameras_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                BoundSettingsSwitch(
                    label = stringResource(R.string.settings_speed_cameras),
                    checked = settings.speedCamerasEnabled.takeIf { store.canEdit },
                    onCheckedChange = viewModel::setSpeedCamerasEnabled,
                    enabled = canChange,
                )
            }
        }

        HorizontalDivider()

        // TTS toggle
        Section(title = stringResource(R.string.settings_tts)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_tts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                BoundSettingsSwitch(
                    label = stringResource(R.string.settings_tts),
                    checked = settings.ttsEnabled.takeIf { store.canEdit },
                    onCheckedChange = viewModel::setTtsEnabled,
                    enabled = canChange,
                )
            }
        }

        HorizontalDivider()

        if (BuildConfig.CAN_DOWNLOAD_PACKS) {
            Section(title = stringResource(R.string.settings_api_keys)) {
                Text(
                    text = stringResource(R.string.settings_api_keys_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                ApiKeyField(
                    label = stringResource(R.string.settings_nsw_api_key),
                    value = nswDraft ?: settings.nswTrafficApiKey,
                    onValueChange = viewModel::setNswTrafficApiKey,
                    placeholder = stringResource(R.string.settings_nsw_api_key_placeholder),
                    // Drafts and the serialized writer safely retain typing during a pending save.
                    enabled = store.canEdit,
                )
                ApiKeyField(
                    label = stringResource(R.string.settings_vic_api_key),
                    value = vicDraft ?: settings.vicTrafficApiKey,
                    onValueChange = viewModel::setVicTrafficApiKey,
                    placeholder = stringResource(R.string.settings_vic_api_key_placeholder),
                    enabled = store.canEdit,
                )
            }
            HorizontalDivider()
        }

        // Attribution toggle
        Section(title = stringResource(R.string.settings_show_attribution)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.about_osm_attribution),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                BoundSettingsSwitch(
                    label = stringResource(R.string.settings_show_attribution),
                    checked = settings.showAttributionOverlay.takeIf { store.canEdit },
                    onCheckedChange = viewModel::setShowAttribution,
                    enabled = canChange,
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
                    error = packUiState.error,
                    workerError = workerError,
                    info = packUiState.info,
                    showUninstallConfirm = packUiState.showUninstallConfirm,
                    isDeleting = packUiState.isDeleting,
                    onDownloadClick = mapPackViewModel::onDownloadClick,
                    onCancelClick = mapPackViewModel::onCancelClick,
                    onRetryClick = mapPackViewModel::onRetryClick,
                    onUninstallClick = mapPackViewModel::onUninstallClick,
                    onUninstallDismissed = mapPackViewModel::onUninstallDismissed,
                    onUninstallConfirmed = mapPackViewModel::onUninstallConfirmed,
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
                text = stringResource(
                    if (BuildConfig.CAN_DOWNLOAD_PACKS) R.string.about_privacy_network
                    else R.string.about_privacy_offline
                ),
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
}

@Composable
private fun BoundSettingsSwitch(
    label: String,
    checked: Boolean?,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    if (checked == null) {
        Text(stringResource(R.string.settings_store_unknown), style = MaterialTheme.typography.bodySmall)
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun ThemeModeSelector(
    selected: ThemeMode?,
    onSelect: (ThemeMode) -> Unit,
    enabled: Boolean,
) {
    val options =
        listOf(
            ThemeMode.System to stringResource(R.string.settings_theme_system),
            ThemeMode.Light to stringResource(R.string.settings_theme_light),
            ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
        )
    Column {
        if (selected == null)
            Text(stringResource(R.string.settings_store_unknown), style = MaterialTheme.typography.bodySmall)
        options.forEach { (mode, label) ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .selectable(
                            selected = selected == mode,
                            onClick = { onSelect(mode) },
                            enabled = enabled,
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == mode,
                    onClick = null, // handled by selectable
                    enabled = enabled,
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        content()
    }
}

@Composable
internal fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        enabled = enabled,
    )
}

@Suppress("LongParameterList")
@Composable
internal fun MapPackSection(
    installedPack: InstalledPack?,
    downloadProgress: DownloadProgress?,
    isChecking: Boolean,
    error: String?,
    workerError: String?,
    info: String?,
    showUninstallConfirm: Boolean,
    isDeleting: Boolean,
    onDownloadClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    onUninstallClick: () -> Unit,
    onUninstallDismissed: () -> Unit,
    onUninstallConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val cancelFailed = workerError == DOWNLOAD_CANCEL_FAILED
        if (downloadProgress != null) {
            Text(
                text =
                    when (downloadProgress.phase) {
                        DownloadProgress.Phase.QUEUED ->
                            stringResource(R.string.download_queued)
                        DownloadProgress.Phase.FETCHING_MANIFEST ->
                            stringResource(R.string.download_checking)
                        DownloadProgress.Phase.DOWNLOADING -> {
                            val downloaded = formatBytes(downloadProgress.bytesDownloaded)
                            val total = downloadProgress.totalBytes?.let { formatBytes(it) } ?: "?"
                            stringResource(R.string.download_downloading, downloaded, total)
                        }
                        DownloadProgress.Phase.VERIFYING ->
                            stringResource(R.string.download_verifying)
                        DownloadProgress.Phase.EXTRACTING ->
                            stringResource(R.string.download_extracting)
                        DownloadProgress.Phase.INSTALLING ->
                            stringResource(R.string.download_installing)
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
            val totalBytes = downloadProgress.totalBytes
            if (totalBytes != null && totalBytes > 0) {
                LinearProgressIndicator(
                    progress = {
                        downloadProgress.bytesDownloaded.toFloat() / totalBytes.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onCancelClick) {
                Text(stringResource(R.string.cancel))
            }
        } else if (!cancelFailed && installedPack != null && installedPack.version.isNotEmpty()) {
            Text(
                text = stringResource(R.string.settings_map_pack_installed),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    stringResource(
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
            TextButton(onClick = onUninstallClick, enabled = !isDeleting) {
                Text(stringResource(R.string.settings_map_pack_uninstall))
            }
        } else if (!cancelFailed) {
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
            // Only fixed, known public messages may cross the presentation boundary.
            val knownErrors = listOf(
                stringResource(R.string.download_failed_not_found),
                stringResource(R.string.download_failed_unreachable),
                stringResource(R.string.download_failed_invalid),
                stringResource(R.string.download_failed_checksum),
                stringResource(R.string.download_unavailable_in_build),
                stringResource(R.string.map_pack_uninstall_failed),
            )
            Text(
                text = knownErrors.firstOrNull { it == error } ?: stringResource(R.string.map_pack_action_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Rejected Cancel is not a failed/stopped download. Keep the existing
        // Cancel action (also when no native progress has been observed yet).
        if (workerError != null) {
            Text(
                text = stringResource(
                    if (cancelFailed) R.string.map_pack_cancel_failed else R.string.map_pack_worker_error,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            if (cancelFailed) {
                if (downloadProgress == null) {
                    TextButton(onClick = onCancelClick) { Text(stringResource(R.string.cancel)) }
                }
            } else {
                Button(onClick = onRetryClick, enabled = !isChecking) {
                    Text(stringResource(R.string.map_pack_retry))
                }
            }
        }
        val safeInfo = listOf(
            stringResource(R.string.download_already_up_to_date),
            stringResource(R.string.map_pack_uninstalled),
        ).firstOrNull { it == info }
        if (safeInfo != null) {
            Text(
                text = safeInfo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showUninstallConfirm) {
            AlertDialog(
                onDismissRequest = onUninstallDismissed,
                title = { Text(stringResource(R.string.map_pack_uninstall_title)) },
                text = { Text(stringResource(R.string.map_pack_uninstall_message)) },
                confirmButton = {
                    TextButton(onClick = onUninstallConfirmed) {
                        Text(stringResource(R.string.settings_map_pack_uninstall))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onUninstallDismissed) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

private const val KB = 1024L
private const val MB = KB * 1024
private const val GB = MB * 1024

private fun formatBytes(bytes: Long): String =
    when {
        bytes < KB -> "$bytes B"
        bytes < MB -> "${bytes / KB} KB"
        bytes < GB -> "%.1f MB".format(bytes / MB.toDouble())
        else -> "%.1f GB".format(bytes / GB.toDouble())
    }

@Composable
internal fun SettingsStoreNotice(state: SettingsStoreState, onRetry: () -> Unit) {
    val message = when {
        state.readFailed -> R.string.settings_store_read_error
        state.writeFailed -> R.string.settings_store_write_error
        state.isSaving -> R.string.settings_store_saving
        !state.hasReadValue -> R.string.settings_store_loading
        else -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(message),
            color = if (state.readFailed || state.writeFailed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        val progressMessage = when {
            state.isSaving && message != R.string.settings_store_saving -> R.string.settings_store_saving
            state.retryingRead && message != R.string.settings_store_loading -> R.string.settings_store_loading
            else -> null
        }
        if (progressMessage != null)
            Text(stringResource(progressMessage), style = MaterialTheme.typography.bodySmall)
        if (state.readFailed || state.writeFailed) {
            TextButton(onClick = onRetry, enabled = !state.isSaving && !state.retryingRead) {
                Text(stringResource(R.string.settings_store_retry))
            }
        }
    }
}
