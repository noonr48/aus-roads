package au.com.ausroads.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.R
import au.com.ausroads.di.PackRuntimeCoordinator
import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.ManifestFetchResult
import au.com.ausroads.offline.pack.PackManifest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class MapPackViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mapPackManager: MapPackManager,
    packRuntimeCoordinator: PackRuntimeCoordinator,
    /** Pack CDN base URL from BuildConfig — single source for the zip URL too. */
    @Named("mapPackBaseUrl") private val baseUrl: String,
) : ViewModel() {

    val installed: StateFlow<InstalledPack?> = mapPackManager.installed
    val installedStateInitialized: StateFlow<Boolean> = mapPackManager.installedStateInitialized
    val renderInstalled: StateFlow<InstalledPack?> = packRuntimeCoordinator.activePack
    val renderStateInitialized: StateFlow<Boolean> = packRuntimeCoordinator.initialized
    val inFlight: StateFlow<DownloadProgress?> = mapPackManager.inFlight

    /** Error from the most recent FAILED download worker run (verify/extract/etc.). */
    val downloadError: StateFlow<String?> = mapPackManager.downloadError

    private val _uiState = MutableStateFlow(MapPackUiState())
    val uiState: StateFlow<MapPackUiState> = _uiState.asStateFlow()

    fun onDownloadClick() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, error = null) }
            when (val result = mapPackManager.fetchLatestManifest()) {
                // This action is also the explicit repair/re-download control.
                // A 304 still carries the cached manifest and must be allowed to
                // replace a damaged pack even when the version is unchanged.
                is ManifestFetchResult.Fresh -> startDownload(result.manifest, result.rawJson)
                is ManifestFetchResult.Unchanged -> startDownload(result.manifest, result.rawJson)
                is ManifestFetchResult.Failed -> {
                    _uiState.update { it.copy(isChecking = false, error = friendlyFailure(result.reason)) }
                }
            }
        }
    }

    /**
     * Start or explicitly repair the latest pack. The settings control says
     * "Re-download map" when a pack exists, so same-version replacement is an
     * intentional user action rather than an "already up to date" no-op.
     */
    private fun startDownload(manifest: PackManifest, rawJson: String) {
        // Flat asset name — GitHub Release assets can't contain slashes (<base>/pack.zip);
        // rawJson is threaded so the worker verifies components against their hashes.
        mapPackManager.startDownload("$baseUrl/pack.zip", manifest.packVersion, rawJson)
        _uiState.update { it.copy(isChecking = false) }
    }

    fun onCancelClick() {
        mapPackManager.cancelDownload()
    }

    /** Map an internal failure reason to a user-facing message (no raw enum names). */
    private fun friendlyFailure(reason: ManifestFetchResult.FailureReason): String =
        context.getString(
            when (reason) {
                ManifestFetchResult.FailureReason.NOT_FOUND -> R.string.download_failed_not_found
                ManifestFetchResult.FailureReason.UNREACHABLE -> R.string.download_failed_unreachable
                ManifestFetchResult.FailureReason.INVALID -> R.string.download_failed_invalid
                ManifestFetchResult.FailureReason.CHECKSUM_MISMATCH -> R.string.download_failed_checksum
                ManifestFetchResult.FailureReason.DOWNLOADS_UNAVAILABLE ->
                    R.string.download_unavailable_in_build
            },
        )

}

data class MapPackUiState(
    val isChecking: Boolean = false,
    val error: String? = null,
)
