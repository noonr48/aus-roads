package au.com.ausroads.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.R
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
    /** Pack CDN base URL from BuildConfig — single source for the zip URL too. */
    @Named("mapPackBaseUrl") private val baseUrl: String,
) : ViewModel() {

    val installed: StateFlow<InstalledPack?> = mapPackManager.installed
    val inFlight: StateFlow<DownloadProgress?> = mapPackManager.inFlight

    /** Error from the most recent FAILED download worker run (verify/extract/etc.). */
    val downloadError: StateFlow<String?> = mapPackManager.downloadError

    private val _uiState = MutableStateFlow(MapPackUiState())
    val uiState: StateFlow<MapPackUiState> = _uiState.asStateFlow()

    fun onDownloadClick() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, error = null) }
            when (val result = mapPackManager.fetchLatestManifest()) {
                // Fresh (200) and Unchanged (304) both carry the latest manifest. The
                // download decision compares its version to the INSTALLED pack, not the
                // manifest's freshness, so a download that failed after the manifest was
                // cached stays retryable.
                is ManifestFetchResult.Fresh -> startDownloadIfOutdated(result.manifest, result.rawJson)
                is ManifestFetchResult.Unchanged -> startDownloadIfOutdated(result.manifest, result.rawJson)
                is ManifestFetchResult.Failed -> {
                    _uiState.update { it.copy(isChecking = false, error = friendlyFailure(result.reason)) }
                }
            }
        }
    }

    /**
     * Start a download only if the latest manifest's pack version differs from the
     * installed pack. Comparing against the installed version (not the manifest's
     * freshness) means a download that failed after the manifest was cached can be
     * retried, while an already up-to-date pack is not needlessly re-fetched.
     */
    private fun startDownloadIfOutdated(manifest: PackManifest, rawJson: String) {
        if (installed.value?.version == manifest.packVersion) {
            _uiState.update {
                it.copy(
                    isChecking = false,
                    error = context.getString(R.string.download_already_up_to_date),
                )
            }
            return
        }
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
