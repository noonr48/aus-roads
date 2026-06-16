/*
 * TypeConverter for kotlinx.datetime.Instant ↔ Long (epoch millis).
 * Registered in PinDatabase so Room can persist Instant fields without
 * hitting the KSP "unexpected jvm signature V" error that Room 2.6.1 +
 * KSP 2.x produce when they encounter unhandled Kotlin types.
 */
package au.com.ausroads.data.pins

import androidx.room.TypeConverter
import kotlinx.datetime.Instant

class InstantConverters {
    @TypeConverter
    fun fromInstant(value: Instant): Long = value.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(epochMillis: Long): Instant = Instant.fromEpochMilliseconds(epochMillis)
}
