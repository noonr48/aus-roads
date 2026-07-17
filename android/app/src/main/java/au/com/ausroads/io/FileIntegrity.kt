package au.com.ausroads.io

import java.io.File
import java.security.MessageDigest

internal fun hasSqliteHeader(file: File): Boolean =
    file.isFile && file.length() > SQLITE_HEADER_SIZE && file.inputStream().use { input ->
        val header = ByteArray(SQLITE_HEADER_SIZE)
        input.read(header) == header.size &&
            String(header, Charsets.US_ASCII) == SQLITE_HEADER
    }

internal fun isExpectedSqliteDatabase(file: File, expectedSha256: String): Boolean =
    hasSqliteHeader(file) && file.sha256() == expectedSha256

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private const val SQLITE_HEADER_SIZE = 16
private const val SQLITE_HEADER = "SQLite format 3\u0000"
