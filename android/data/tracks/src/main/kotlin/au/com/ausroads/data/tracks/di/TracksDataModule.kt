/*
 * Hilt module for :data:tracks. Self-contained (mirrors :data:routes RoutesModule) so :app can
 * depend on this module without wiring the tracks DB/DAO/repo itself — unlike :data:pins, whose
 * DB/DAO/repo are still provided in :app/AppModule.
 *
 * Repository binding uses a @Provides function (impl -> interface), matching AppModule and
 * RoutesModule, rather than @Binds — that would force a second abstract @Module here.
 */
package au.com.ausroads.data.tracks.di

import android.content.Context
import androidx.room.Room
import au.com.ausroads.data.tracks.RoomTrackRepository
import au.com.ausroads.data.tracks.TrackDao
import au.com.ausroads.data.tracks.TrackDatabase
import au.com.ausroads.data.tracks.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TracksDataModule {

    @Provides
    @Singleton
    fun provideTrackDatabase(
        @ApplicationContext context: Context,
    ): TrackDatabase = Room.databaseBuilder(
        context,
        TrackDatabase::class.java,
        TrackDatabase.DATABASE_NAME,
    )
        // v1 schema: no migrations yet. Destructive fallback is the last-resort net for an
        // unknown on-disk schema, matching RoutesModule. Replace with addMigrations(...) when
        // the first tracks schema bump lands.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    @Provides
    fun provideTrackDao(db: TrackDatabase): TrackDao = db.trackDao()

    @Provides
    @Singleton
    fun provideTrackRepository(impl: RoomTrackRepository): TrackRepository = impl
}
