/*
 * Hilt wiring for :data:reports (community hazard reports, ADR 0004 v0.5+).
 * Mirrors AppModule's pin wiring exactly: one Room database provider with
 * destructive fallback as the last-resort net (v1 has no migration history
 * yet), then DAO and repository-interface providers.
 */
package au.com.ausroads.di

import android.content.Context
import androidx.room.Room
import au.com.ausroads.data.reports.CommunityReportDao
import au.com.ausroads.data.reports.CommunityReportRepository
import au.com.ausroads.data.reports.ReportDatabase
import au.com.ausroads.data.reports.RoomCommunityReportRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReportsModule {

    @Provides
    @Singleton
    fun provideReportDatabase(
        @ApplicationContext context: Context,
    ): ReportDatabase = Room.databaseBuilder(
        context,
        ReportDatabase::class.java,
        ReportDatabase.DATABASE_NAME,
    )
        // No migrations yet (schema v1); destructive fallback stays only as a
        // last-resort net for an unknown on-disk schema. Add real migrations
        // here before any schema bump, following AppModule/PinDatabase.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    @Provides
    fun provideCommunityReportDao(db: ReportDatabase): CommunityReportDao =
        db.communityReportDao()

    @Provides
    @Singleton
    fun provideCommunityReportRepository(
        impl: RoomCommunityReportRepository,
    ): CommunityReportRepository = impl
}
