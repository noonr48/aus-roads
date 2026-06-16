/*
 * Geographic region descriptor used across the app.
 * Pure Kotlin; no Android types. AU-SA, AU-NSW, AU-VIC, etc. all share this shape.
 */
package au.com.ausroads.core.model

import kotlinx.serialization.Serializable

/**
 * Identifies a geographic region. Used as the key in the live traffic provider registry
 * and as the manifest `region` field for the offline map pack.
 */
@Serializable
data class Region(
    /** ISO 3166-1 alpha-2 country code, e.g. "AU". */
    val country: String,
    /** Optional subdivision: state / territory / province. Lowercase, e.g. "sa", "nsw". */
    val state: String? = null,
) {
    /** Canonical region code, e.g. "AU-SA". Used as `LiveTrafficProvider.regionCode`. */
    val code: String
        get() = if (state != null) "${country.uppercase()}-${state.uppercase()}" else country.uppercase()

    companion object {
        val AU_SA = Region(country = "AU", state = "sa")
        val AU_NSW = Region(country = "AU", state = "nsw")
        val AU_VIC = Region(country = "AU", state = "vic")
        val AU_QLD = Region(country = "AU", state = "qld")
        val AU_WA = Region(country = "AU", state = "wa")
        val AU_TAS = Region(country = "AU", state = "tas")
        val AU_NT = Region(country = "AU", state = "nt")
        val AU_ACT = Region(country = "AU", state = "act")
        val AU_NATIONAL = Region(country = "AU")
    }
}
