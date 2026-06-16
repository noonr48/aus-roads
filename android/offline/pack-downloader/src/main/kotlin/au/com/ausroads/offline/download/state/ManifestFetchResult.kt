package au.com.ausroads.offline.download.state

import au.com.ausroads.offline.pack.PackManifest

sealed interface ManifestFetchResult {
    /**
     * [rawJson] is the canonical JSON the manifest was (de)serialized from. It is
     * threaded to the download worker so the verifier hashes the exact bytes and
     * the install record's manifestSha256 is stable.
     */
    data class Fresh(val manifest: PackManifest, val rawJson: String) : ManifestFetchResult
    data object Unchanged : ManifestFetchResult
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
