package au.com.ausroads.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.ausroads.R
import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.ManifestFetchResult
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
                is ManifestFetchResult.Fresh -> {
                    val manifest = result.manifest
                    val packUrl = "$baseUrl/packs/${manifest.packVersion}/pack.zip"
                    // Pass the canonical manifest JSON so the worker can verify the
                    // downloaded components against their declared hashes.
                    mapPackManager.startDownload(packUrl, manifest.packVersion, result.rawJson)
                    _uiState.update { it.copy(isChecking = false) }
                }
                is ManifestFetchResult.Unchanged -> {
                    val msg = context.getString(R.string.download_already_up_to_date)
                    _uiState.update { it.copy(isChecking = false, error = msg) }
                }
                is ManifestFetchResult.Failed -> {
                    _uiState.update { it.copy(isChecking = false, error = friendlyFailure(result.reason)) }
                }
            }
        }
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
