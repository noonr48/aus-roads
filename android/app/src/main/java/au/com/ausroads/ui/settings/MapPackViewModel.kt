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
import kotlinx.coroutines.CancellationException
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

    private var lastAttempt: DownloadAttempt? = null

    fun onDownloadClick() {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, error = null, info = null) }
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
     * Retry the most recent failed download, reusing MapPackManager retry
     * semantics (startDownload clears the stale worker failure before
     * re-enqueuing). Falls back to the full manifest check when no download
     * was previously attempted.
     */
    fun onRetryClick() {
        val attempt = lastAttempt
        if (attempt == null) {
            onDownloadClick()
        } else {
            mapPackManager.startDownload(attempt.packUrl, attempt.packVersion, attempt.manifestJson)
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
                    // "Already up to date" is an outcome, not a failure — surface it
                    // on the info channel so the UI doesn't paint it red.
                    info = context.getString(R.string.download_already_up_to_date),
                )
            }
            return
        }
        // Flat asset name — GitHub Release assets can't contain slashes (<base>/pack.zip);
        // rawJson is threaded so the worker verifies components against their hashes.
        val attempt = DownloadAttempt(
            packUrl = "$baseUrl/pack.zip",
            packVersion = manifest.packVersion,
            manifestJson = rawJson,
        )
        lastAttempt = attempt
        mapPackManager.startDownload(attempt.packUrl, attempt.packVersion, attempt.manifestJson)
        _uiState.update { it.copy(isChecking = false) }
    }

    fun onCancelClick() {
        mapPackManager.cancelDownload()
    }

    /** Opens the uninstall confirmation dialog for the installed pack. */
    fun onUninstallClick() {
        _uiState.update { it.copy(showUninstallConfirm = true, info = null) }
    }

    fun onUninstallDismissed() {
        _uiState.update { it.copy(showUninstallConfirm = false) }
    }

    /** Confirmed uninstall: delete the pack; outcome lands on info (success) or error (failure). */
    @Suppress("TooGenericExceptionCaught") // storage deletion can fail in many ways; surface it
    fun onUninstallConfirmed() {
        _uiState.update { it.copy(showUninstallConfirm = false, isDeleting = true, error = null) }
        viewModelScope.launch {
            val result = try {
                Result.success(mapPackManager.deleteInstalled())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { deleted ->
                    if (deleted) {
                        _uiState.update {
                            it.copy(isDeleting = false, info = context.getString(R.string.map_pack_uninstalled))
                        }
                    } else {
                        // Nothing was deleted (e.g. raced with another removal) — a
                        // failure, not a silent success.
                        _uiState.update {
                            it.copy(isDeleting = false, error = context.getString(R.string.map_pack_uninstall_failed))
                        }
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            error = context.getString(R.string.download_failed_generic, e.message ?: ""),
                        )
                    }
                },
            )
        }
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
    /** Non-failure outcome message (e.g. "Already up to date"), rendered neutrally. */
    val info: String? = null,
    /** Whether the uninstall-confirmation dialog is showing. */
    val showUninstallConfirm: Boolean = false,
    /** True while the installed pack is being deleted. */
    val isDeleting: Boolean = false,
)

/** Args of the most recent download attempt, kept so a failed run can be retried verbatim. */
private data class DownloadAttempt(
    val packUrl: String,
    val packVersion: String,
    val manifestJson: String?,
)
