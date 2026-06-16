package au.com.ausroads.offline.download.download

import au.com.ausroads.offline.pack.PackManifest
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class PackVerifier {

    sealed interface VerificationResult {
        data object Ok : VerificationResult
        data class Mismatch(
            val component: String,
            val expected: String,
            val actual: String,
        ) : VerificationResult
    }

    fun verify(installDir: File, manifest: PackManifest): VerificationResult {
        val components = manifest.components

        // Verify tiles
        components.tiles?.let { tiles ->
            val file = File(installDir, tiles.path)
            if (!file.exists()) return VerificationResult.Mismatch("tiles", tiles.sha256, "file_missing")
            val actual = sha256(file)
            if (actual != tiles.sha256.lowercase()) {
                return VerificationResult.Mismatch("tiles", tiles.sha256, actual)
            }
        }

        // Verify search
        components.search?.let { search ->
            if (search.format == "none") return@let
            val file = File(installDir, search.path)
            if (!file.exists()) return VerificationResult.Mismatch("search", search.sha256, "file_missing")
            val actual = sha256(file)
            if (actual != search.sha256.lowercase()) {
                return VerificationResult.Mismatch("search", search.sha256, actual)
            }
        }

        // Verify routing
        components.routing?.let { routing ->
            if (routing.format == "none") return@let
            val file = File(installDir, routing.path)
            if (!file.exists()) return VerificationResult.Mismatch("routing", routing.sha256, "file_missing")
            val actual = sha256(file)
            if (actual != routing.sha256.lowercase()) {
                return VerificationResult.Mismatch("routing", routing.sha256, actual)
            }
        }

        return VerificationResult.Ok
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
