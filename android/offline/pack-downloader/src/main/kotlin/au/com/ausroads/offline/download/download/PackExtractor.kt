package au.com.ausroads.offline.download.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

class PackExtractor {

    suspend fun extract(zipFile: File, targetDir: File) = withContext(Dispatchers.IO) {
        targetDir.mkdirs()

        try {
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(targetDir, entry.name)

                        // Security: prevent zip-slip attacks
                        if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                            throw SecurityException("Zip entry path escapes target dir: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { fos ->
                                zis.copyTo(fos)
                            }
                        }

                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            targetDir.deleteRecursively()
            throw e
        }
    }
}
