package au.com.ausroads.offline.download.state

import au.com.ausroads.offline.pack.PackManifest

sealed interface ManifestFetchResult {
    /**
     * [rawJson] is the canonical JSON the manifest was (de)serialized from. It is
     * threaded to the download worker so the verifier hashes the exact bytes and
     * the install record's manifestSha256 is stable.
     */
    data class Fresh(val manifest: PackManifest, val rawJson: String) : ManifestFetchResult

    /**
     * The server reported the manifest unchanged (HTTP 304); [manifest]/[rawJson]
     * are the still-current values from cache. Carried (not a bare object) so the
     * caller can compare the latest version against what is installed and decide to
     * re-download — an unchanged manifest is NOT proof the pack is installed (e.g. a
     * download that failed after the manifest was cached must stay retryable).
     */
    data class Unchanged(val manifest: PackManifest, val rawJson: String) : ManifestFetchResult

    data class Failed(val reason: FailureReason) : ManifestFetchResult

    enum class FailureReason {
        NOT_FOUND,
        UNREACHABLE,
        INVALID,
        CHECKSUM_MISMATCH,

        /** This build has no configured download endpoint (e.g. the offline flavor). */
        DOWNLOADS_UNAVAILABLE,
    }
}
