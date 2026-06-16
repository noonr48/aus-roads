package au.com.ausroads.di

import au.com.ausroads.traffic.provider.LiveTrafficProvider
import au.com.ausroads.traffic.provider.sa.TrafficSaProvider
import au.com.ausroads.traffic.provider.sa.outback.SaOutbackProvider
import au.com.ausroads.traffic.provider.vic.VicRoadsProvider
import au.com.ausroads.traffic.provider.nsw.NswLiveTrafficProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import javax.inject.Singleton

/**
 * withNetwork flavor: binds the live regional traffic providers. This flavor
 * declares INTERNET, so these network-backed providers are wired here.
 *
 * The offline flavor binds a no-op stub instead (see app/src/offline) — it has no
 * INTERNET permission, and a network attempt there throws an uncaught
 * SecurityException on OkHttp's dispatcher thread that no caller try/catch can
 * intercept. Keeping the live providers out of the offline graph enforces the
 * no-network posture at the DI layer.
 */
@Module
@InstallIn(SingletonComponent::class)
object TrafficModule {
    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideTrafficProviders(
        trafficSaProvider: TrafficSaProvider,
        outbackProvider: SaOutbackProvider,
        vicRoadsProvider: VicRoadsProvider,
        nswProvider: NswLiveTrafficProvider,
    ): Set<LiveTrafficProvider> = setOf(trafficSaProvider, outbackProvider, vicRoadsProvider, nswProvider)
}
