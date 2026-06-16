/*
 * Room TypeConverter for Region. Stores the canonical code ("AU-SA", "AU", etc.)
 * — round-trips perfectly because Region.code is a deterministic function of
 * country + state.
 */
package au.com.ausroads.data.pack

import androidx.room.TypeConverter
import au.com.ausroads.core.model.Region

class RegionConverter {
    @TypeConverter
    fun fromRegion(region: Region): String = region.code

    @TypeConverter
    fun toRegion(code: String): Region {
        val parts = code.split("-", limit = 2)
        return if (parts.size == 2) {
            Region(country = parts[0], state = parts[1].lowercase())
        } else {
            Region(country = code)
        }
    }
}
