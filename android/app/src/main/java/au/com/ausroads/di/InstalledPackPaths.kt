package au.com.ausroads.di

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.json.JSONTokener

/** The adopted current.json is authority; a newer orphan directory is not an installed pack. */
object InstalledPackPaths {
    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    @Suppress(
        "TooGenericExceptionCaught"
    ) // corrupt or inaccessible adoption metadata is explicitly rejected
    fun component(context: Context, field: String, legacyDefault: String): File? {
        val base = File(context.filesDir, "mappacks/au-sa")
        val current = File(base, "current.json")
        if (!current.isFile) return null
        return try {
            require(current.length() <= MAX_STATE_BYTES)
            val json = JSONObject(current.readText())
            resolve(base, json.optString("version"), json.optString(field, legacyDefault))
        } catch (_: Exception) {
            Log.e("InstalledPackPaths", "component=places seam=adopted-pack class=contract-drift")
            null
        }
    }

    sealed interface RoutingSelection {
        data class Available(val file: File) : RoutingSelection

        data object Unavailable : RoutingSelection

        data object Rejected : RoutingSelection
    }

    /** Optional routing has no legacy default or orphan/previous-pack fallback. */
    @Suppress("TooGenericExceptionCaught") // malformed/inaccessible authority is a fixed rejection
    fun routing(context: Context): RoutingSelection = try {
        // Canonicalize the trusted Android files root, not a declared pack/component path.
        val base = File(context.filesDir.canonicalFile, "mappacks/au-sa")
        if (base.absoluteFile != base.canonicalFile) {
            RoutingSelection.Rejected
        } else {
            val current = File(base, "current.json")
            when {
                Files.isSymbolicLink(current.toPath()) -> RoutingSelection.Rejected
                Files.notExists(current.toPath()) -> RoutingSelection.Unavailable
                !current.isFile || current.length() > MAX_STATE_BYTES -> RoutingSelection.Rejected
                else -> {
                    val json = readRoutingState(current)
                    resolveRouting(
                        base,
                        json.opt("version"),
                        json.opt("routingPath").takeUnless { it === JSONObject.NULL },
                    )
                }
            }
        }
    } catch (_: Exception) {
        RoutingSelection.Rejected
    }

    /** Typed metadata/containment boundary; JSONObject parsing is covered on real Android. */
    internal fun resolveRouting(baseDir: File, version: Any?, relativePath: Any?): RoutingSelection = when {
        version !is String || !versionPattern.matches(version) -> RoutingSelection.Rejected
        relativePath == null -> RoutingSelection.Unavailable
        relativePath !is String || relativePath.isBlank() -> RoutingSelection.Rejected
        else -> resolveRoutingFile(baseDir, version, relativePath)
    }

    @Suppress("TooGenericExceptionCaught") // inaccessible paths remain a fixed rejection
    private fun resolveRoutingFile(baseDir: File, version: String, relativePath: String): RoutingSelection = try {
        val root = baseDir.canonicalFile
        if (baseDir.absoluteFile.toPath().normalize() != root.toPath()) {
            RoutingSelection.Rejected
        } else {
            val component = resolve(root, version, relativePath)
            when {
                component == null || declaredPathHasSymlink(root, version, relativePath) -> RoutingSelection.Rejected
                else -> RoutingSelection.Available(component)
            }
        }
    } catch (_: Exception) {
        RoutingSelection.Rejected
    }

    private fun declaredPathHasSymlink(root: File, version: String, relativePath: String): Boolean {
        var declared = File(root, "v$version")
        if (Files.isSymbolicLink(declared.toPath())) return true
        // Walk before normalization: even an in-root link followed by /.. is rejected.
        return relativePath.split('/').any { segment ->
            declared = File(declared, segment)
            Files.isSymbolicLink(declared.toPath())
        }
    }

    private fun readRoutingState(current: File): JSONObject = current.inputStream().use { input ->
        // Bound the read itself as well as the length check if metadata grows while being read.
        val bytes = ByteArray(MAX_STATE_BYTES.toInt() + 1)
        var total = 0
        while (total < bytes.size) {
            val count = input.read(bytes, total, bytes.size - total)
            if (count == -1) break
            total += count
        }
        require(total <= MAX_STATE_BYTES)
        val tokener = JSONTokener(String(bytes, 0, total, Charsets.UTF_8))
        val json = tokener.nextValue()
        require(json is JSONObject && tokener.nextClean() == '\u0000' && !tokener.more())
        json
    }

    /** Pure filesystem part, shared by map/search and covered by path-escape/symlink kill tests. */
    fun resolve(baseDir: File, version: String, relativePath: String): File? {
        if (!versionPattern.matches(version) || relativePath.isBlank()) return null
        if (File(relativePath).isAbsolute || '\u0000' in relativePath || '\\' in relativePath)
            return null
        val root = baseDir.canonicalFile
        val adopted = File(root, "v$version").canonicalFile
        val component = File(adopted, relativePath).canonicalFile
        if (!contained(adopted, root) || !contained(component, adopted)) return null
        return component.takeIf { it.isFile && it.length() > 0 }
    }

    private fun contained(file: File, parent: File): Boolean =
        file != parent && file.toPath().startsWith(parent.toPath())

    private const val MAX_STATE_BYTES = 16_384L
}
