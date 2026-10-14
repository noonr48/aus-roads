package au.com.ausroads.feature.search

/** Fixed query-outcome seam; no query text, coordinates, result names or private paths. */
interface SearchHealth {
    fun success()

    fun failure()
}
