package au.com.ausroads.offline.download.download

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

class PackDownloader(private val client: HttpClient) {

    /**
     * Streams [url] to [target], resuming from a partial file via a Range request.
     *
     * Uses prepareGet().execute { } and reads the body as a STREAMING channel.
     * `client.get(url).body()` would force Ktor to SAVE the whole response into a
     * single in-memory ByteArray first (io.ktor.client.call.SavedCall.save), which
     * OOMs on a multi-hundred-MB pack — a 155 MB pack threw OutOfMemoryError trying
     * to allocate one 155 MB array under the 256 MB heap. Here the bytes flow
     * straight to disk in [DOWNLOAD_BUFFER_BYTES] chunks and are never all resident.
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()

        val existingBytes = if (target.exists()) target.length() else 0L
        var rangeRejected = false

        client.prepareGet(url) {
            if (existingBytes > 0) {
                header(HttpHeaders.Range, "bytes=$existingBytes-")
            }
        }.execute { response ->
            if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                // Stale/invalid partial — fall through to a clean restart below.
                rangeRejected = true
                return@execute
            }

            val totalBytes: Long? = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            val isResume = response.status == HttpStatusCode.PartialContent

            val channel = response.bodyAsChannel()
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            var bytesWritten = if (isResume) existingBytes else 0L

            FileOutputStream(target, isResume).use { fos ->
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) {
                        fos.write(buffer, 0, read)
                        bytesWritten += read
                        onProgress(bytesWritten, totalBytes)
                    }
                }
            }
        }

        if (rangeRejected && existingBytes > 0) {
            // We sent a resume Range and the server rejected it (stale/invalid partial):
            // discard it and retry once from scratch. The retry sends no Range, so it
            // cannot 416 again — bounded recursion, never a loop even on a broken server.
            target.delete()
            return@withContext download(url, target, onProgress)
        }

        target
    }
}
