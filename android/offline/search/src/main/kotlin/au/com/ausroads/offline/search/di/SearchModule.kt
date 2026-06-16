package au.com.ausroads.offline.search.di

import au.com.ausroads.offline.search.FtsSearchRepository
import au.com.ausroads.offline.search.SearchRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchModule {
    @Binds
    abstract fun bindSearchRepository(impl: FtsSearchRepository): SearchRepository
}
