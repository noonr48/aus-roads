/*
 * TypeConverter for kotlinx.datetime.InstalledPackEntity's Instant fields.
 * Duplicated from :data:pins (same converter, same package name doesn't apply —
 * different module, different package). v0.2 could consolidate into a :data:common
 * module, but v0.1.1 keeps it simple.
 */
package au.com.ausroads.data.pack

import androidx.room.TypeConverter
import kotlinx.datetime.Instant

class InstantConverters {
    @TypeConverter
    fun fromInstant(value: Instant): Long = value.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(epochMillis: Long): Instant = Instant.fromEpochMilliseconds(epochMillis)
}
