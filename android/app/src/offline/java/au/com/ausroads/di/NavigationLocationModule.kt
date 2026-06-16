package au.com.ausroads.di

import au.com.ausroads.feature.navigation.NavigationLocation
import au.com.ausroads.feature.navigation.NavigationLocationSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * offline flavor: binds a no-op navigation location source. The offline flavor
 * declares no location permission and links no Google Play Services, so there is
 * no live position stream — turn-by-turn navigation falls back to its
 * route-simulated tracking (NavigationViewModel.startSimulatedTracking). Binding a
 * no-op keeps the offline graph location-free by construction.
 */
@Module
@InstallIn(SingletonComponent::class)
object NavigationLocationModule {
    @Provides
    @Singleton
    fun provideNavigationLocationSource(): NavigationLocationSource = OfflineNavigationLocationSource
}

private object OfflineNavigationLocationSource : NavigationLocationSource {
    override fun locationUpdates(intervalMs: Long): Flow<NavigationLocation> = emptyFlow()
}
