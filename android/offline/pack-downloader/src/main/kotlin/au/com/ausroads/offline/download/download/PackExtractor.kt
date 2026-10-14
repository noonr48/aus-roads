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
                        val targetPath = targetDir.canonicalPath
                        val outputPath = outFile.canonicalPath
                        if (outputPath != targetPath && !outputPath.startsWith(targetPath + File.separator)) {
                            throw SecurityException("pack_archive_path_invalid")
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
