/*
 * TypeConverter for kotlinx.datetime.Instant ↔ Long (epoch millis).
 * Registered in ReportDatabase so Room can persist Instant fields.
 *
 * Unlike :data:pins, the signatures here are nullable because a report's
 * expiresAt is Instant? (null = never expires). Room resolves these for both
 * the non-null createdAt and the nullable expiresAt columns.
 */
package au.com.ausroads.data.reports

import androidx.room.TypeConverter
import kotlinx.datetime.Instant

class InstantConverters {
    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(epochMillis: Long?): Instant? =
        epochMillis?.let { Instant.fromEpochMilliseconds(it) }
}
