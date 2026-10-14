package au.com.ausroads.ui.map

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Owns reproducible derived assets, never installed packs or saved user data. */
internal object ValidatedMapAssetCache {
    private const val DIGEST_BUFFER_BYTES = 64 * 1024
    private val lock = Any()

    fun ensure(target: File, source: () -> InputStream, validate: (File) -> Unit): File =
        synchronized(lock) {
            val parent = requireNotNull(target.parentFile).canonicalFile
            val destination = File(parent, target.name)
            require(!Files.isSymbolicLink(destination.toPath())) {
                "component=map seam=asset-cache-path class=contract-drift"
            }
            val expected = source().use(::digest)
            if (destination.isFile && destination.inputStream().use(::digest).contentEquals(expected)) {
                validate(destination)
                return@synchronized destination
            }
            check(parent.isDirectory || parent.mkdirs()) {
                "component=map seam=asset-cache-parent class=wrong-path"
            }
            val candidate = File.createTempFile("map-asset-", ".tmp", parent)
            // Preserve any primary failure and still report cleanup failure, without
            // throwing from finally or silently accepting a partial asset.
            val outcome = runCatching {
                source().use { input ->
                    FileOutputStream(candidate).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                check(candidate.inputStream().use(::digest).contentEquals(expected)) {
                    "component=map seam=asset-cache-bytes class=contract-drift"
                }
                validate(candidate)
                Files.move(
                    candidate.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
                destination
            }
            finishCandidate(candidate, outcome)
        }

    private fun finishCandidate(candidate: File, outcome: Result<File>): File {
        val cleanup = if (candidate.exists() && !candidate.delete()) {
            IllegalStateException("component=map seam=asset-cache-cleanup class=wrong-path")
        } else null
        return outcome.fold(
            onSuccess = { result -> if (cleanup != null) throw cleanup else result },
            onFailure = { primary ->
                if (cleanup != null) primary.addSuppressed(cleanup)
                throw primary
            },
        )
    }

    private fun digest(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest()
    }
}
