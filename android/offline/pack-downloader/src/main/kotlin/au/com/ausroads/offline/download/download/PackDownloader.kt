package au.com.ausroads.offline.download.download

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PackDownloader(private val client: HttpClient) {

    suspend fun download(
        url: String,
        target: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()

        val existingBytes = if (target.exists()) target.length() else 0L

        val response: HttpResponse = client.get(url) {
            if (existingBytes > 0) {
                header(HttpHeaders.Range, "bytes=$existingBytes-")
            }
        }

        val totalBytes: Long? = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val isResume = response.status == HttpStatusCode.PartialContent

        if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
            // Server says the range is invalid — restart from scratch
            target.delete()
            return@withContext download(url, target, onProgress)
        }

        val channel: ByteReadChannel = response.body()
        val outputStream = FileOutputStream(target, isResume)
        val buffer = ByteArray(8192)
        var bytesWritten = if (isResume) existingBytes else 0L

        outputStream.use { fos ->
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer)
                if (read > 0) {
                    fos.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress(bytesWritten, totalBytes)
                }
            }
        }

        target
    }
}
