package au.com.ausroads.data.routes

import androidx.room.TypeConverter
import kotlinx.datetime.Instant

class InstantConverters {
    @TypeConverter
    fun fromInstant(value: Instant): Long = value.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(epochMillis: Long): Instant = Instant.fromEpochMilliseconds(epochMillis)
}
