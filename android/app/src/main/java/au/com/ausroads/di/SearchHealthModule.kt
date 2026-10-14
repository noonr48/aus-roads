package au.com.ausroads.di

import au.com.ausroads.feature.search.SearchHealth
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The feature owns its outcome contract; the app owns persistent, device-only witnesses. */
@Module
@InstallIn(SingletonComponent::class)
object SearchHealthModule {
    @Provides
    fun provideSearchHealth(health: PlacesHealth): SearchHealth =
        object : SearchHealth {
            override fun success() = health.success(PlacesSeam.MAP_SEARCH_QUERY)

            override fun failure() = health.failure(PlacesSeam.MAP_SEARCH_QUERY)
        }
}
