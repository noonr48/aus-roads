package au.com.ausroads.di

import au.com.ausroads.feature.navigation.RoadHazardSource
import au.com.ausroads.offline.search.SearchRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the pack-backed driver-assist source (posted speed limit + fixed speed
 * cameras) for the navigation feature. Mirrors the NavigationLocationModule
 * shape: the implementation is constructed inline so the binding signature
 * carries only already-bound types.
 */
@Module
@InstallIn(SingletonComponent::class)
object RoadHazardModule {

    @Provides
    @Singleton
    fun provideRoadHazardSource(
        searchRepository: SearchRepository,
    ): RoadHazardSource = SearchRoadHazardSource(searchRepository)
}
