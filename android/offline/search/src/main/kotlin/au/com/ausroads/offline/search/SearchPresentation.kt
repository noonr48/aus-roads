package au.com.ausroads.offline.search

import java.util.Locale

/** User-facing labels for the raw search kind and OSM class values stored in a map pack. */
object SearchPresentation {
    fun kindLabel(kind: String): String = when (kind.trim().lowercase(Locale.ROOT)) {
        "suburb" -> "Place"
        "road" -> "Road"
        "poi" -> "Point of interest"
        "water" -> "Water feature"
        "park" -> "Park"
        else -> humanize(kind) ?: "Other"
    }

    fun groupLabel(kind: String): String = when (kind.trim().lowercase(Locale.ROOT)) {
        "suburb" -> "Places"
        "road" -> "Roads"
        "poi" -> "Points of interest"
        "water" -> "Water features"
        "park" -> "Parks"
        else -> humanize(kind)?.let(::pluralize) ?: "Other"
    }

    fun classLabel(className: String?): String? {
        val rawValue = className
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val value = rawValue.substringAfter('=').trim()
        if (value.lowercase(Locale.ROOT) in NON_DESCRIPTIVE_CLASS_VALUES) return null
        return humanize(value)
    }

    private fun humanize(rawValue: String): String? {
        val words = rawValue
            .trim()
            .replace('_', ' ')
            .replace('-', ' ')
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return words.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
    }

    private fun pluralize(label: String): String =
        if (label.endsWith("s", ignoreCase = true)) label else "${label}s"

    private val NON_DESCRIPTIVE_CLASS_VALUES = setOf(
        "0",
        "1",
        "false",
        "no",
        "true",
        "unknown",
        "yes",
    )
}
