package au.com.ausroads.data.routes.di

import android.content.Context
import androidx.room.Room
import au.com.ausroads.data.routes.RouteHistoryDao
import au.com.ausroads.data.routes.RouteHistoryDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RoutesModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RouteHistoryDatabase =
        Room.databaseBuilder(context, RouteHistoryDatabase::class.java, RouteHistoryDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideDao(db: RouteHistoryDatabase): RouteHistoryDao = db.routeHistoryDao()
}
