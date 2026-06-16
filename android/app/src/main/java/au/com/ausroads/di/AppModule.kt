/*
 * App-level Hilt module. v0.1.1: single object, single @Module. Provides every Room
 * database, every DAO, the DataStore<Preferences> for settings, and the three
 * repository interfaces.
 *
 * The repository interfaces (PinRepository, MapPackRepository, SettingsRepository)
 * are bound to their Room/DataStore implementations via @Provides functions rather
 * than @Binds. @Binds would force a second `@Module abstract class` in this file;
 * the v0.1.1 surface is small enough that the @Provides-style binding is clearer.
 */
package au.com.ausroads.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import au.com.ausroads.data.pack.InstalledPackDao
import au.com.ausroads.data.pack.InstalledPackDatabase
import au.com.ausroads.data.pack.MapPackRepository
import au.com.ausroads.data.pack.RoomMapPackRepository
import au.com.ausroads.data.pins.PinDao
import au.com.ausroads.data.pins.PinDatabase
import au.com.ausroads.data.pins.PinRepository
import au.com.ausroads.data.pins.RoomPinRepository
import au.com.ausroads.data.settings.DataStoreSettingsRepository
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.settingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // --- Room: pins -----------------------------------------------------------

    @Provides
    @Singleton
    fun providePinDatabase(
        @ApplicationContext context: Context,
    ): PinDatabase = Room.databaseBuilder(
        context,
        PinDatabase::class.java,
        PinDatabase.DATABASE_NAME,
    )
        // Real migrations preserve user pins across schema bumps; destructive
        // fallback stays only as a last-resort net for an unknown on-disk schema.
        .addMigrations(PinDatabase.MIGRATION_1_2)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    @Provides
    fun providePinDao(db: PinDatabase): PinDao = db.pinDao()

    @Provides
    @Singleton
    fun providePinRepository(impl: RoomPinRepository): PinRepository = impl

    // --- Room: installed packs -----------------------------------------------

    @Provides
    @Singleton
    fun provideInstalledPackDatabase(
        @ApplicationContext context: Context,
    ): InstalledPackDatabase = Room.databaseBuilder(
        context,
        InstalledPackDatabase::class.java,
        InstalledPackDatabase.DATABASE_NAME,
    ).fallbackToDestructiveMigration(dropAllTables = true).build()

    @Provides
    fun provideInstalledPackDao(db: InstalledPackDatabase): InstalledPackDao = db.installedPackDao()

    @Provides
    @Singleton
    fun provideMapPackRepository(impl: RoomMapPackRepository): MapPackRepository = impl

    // --- DataStore: settings -------------------------------------------------

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun provideSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository = impl
}
