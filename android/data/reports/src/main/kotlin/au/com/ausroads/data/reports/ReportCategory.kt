/*
 * Hazard-report category (ADR 0004 v0.5+, docs/notes/privacy.md). Stored in
 * Room as its name string via [ReportCategoryConverters] so the schema stays
 * stable and human-readable across versions.
 */
package au.com.ausroads.data.reports

import androidx.room.TypeConverter

enum class ReportCategory {
    SHOP,
    ACCIDENT,
    MOBILE_SPEED_CAMERA,
    HAZARD,
}

class ReportCategoryConverters {
    @TypeConverter
    fun fromCategory(value: ReportCategory): String = value.name

    @TypeConverter
    fun toCategory(name: String): ReportCategory = ReportCategory.valueOf(name)
}
