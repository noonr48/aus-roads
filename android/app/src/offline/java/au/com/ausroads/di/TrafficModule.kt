package au.com.ausroads.di

import au.com.ausroads.traffic.provider.LiveTrafficProvider
import au.com.ausroads.traffic.provider.stub.StubLiveTrafficProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import javax.inject.Singleton

/**
 * offline flavor: binds ONLY the no-op stub provider, which returns empty feeds
 * without any network access.
 *
 * The offline flavor declares no INTERNET permission. Wiring the live regional
 * providers here would let the widget worker / traffic UI attempt a network
 * fetch, whose DNS lookup throws a SecurityException on OkHttp's async dispatcher
 * thread — uncaught by any caller try/catch, crashing the process. Binding the
 * stub keeps the offline graph network-free by construction.
 */
@Module
@InstallIn(SingletonComponent::class)
object TrafficModule {
    @Provides
    @Singleton
    @ElementsIntoSet
    fun provideTrafficProviders(): Set<LiveTrafficProvider> = setOf(StubLiveTrafficProvider())
}
