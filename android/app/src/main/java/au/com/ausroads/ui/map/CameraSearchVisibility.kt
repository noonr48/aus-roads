package au.com.ausroads.ui.map

/**
 * Only foreground text-search drawing is simplified; navigation/settings retain their authority.
 */
internal fun cameraSearchVisibility(
    enabled: Boolean,
    navigationActive: Boolean,
    query: String,
): Boolean = enabled && (navigationActive || query.isBlank())
