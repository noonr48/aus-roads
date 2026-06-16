package au.com.ausroads.routing.engine.valhalla.di

import au.com.ausroads.routing.engine.RoutingEngine
import au.com.ausroads.routing.engine.valhalla.ValhallaRoutingEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RoutingModule {
    @Binds
    abstract fun bindRoutingEngine(impl: ValhallaRoutingEngine): RoutingEngine
}
