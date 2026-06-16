/*
 * Bridges the app's BuildConfig map-pack settings into the Hilt graph so the
 * :offline:pack-downloader LIBRARY (which can't read the app's BuildConfig) can
 * consume them. The base URL is the single source of truth for both the manifest
 * URL (baseUrl + "/latest.json") and the pack zip URL (baseUrl + "/packs/<ver>/pack.zip").
 *
 * Qualifiers:
 *   @Named("mapPackBaseUrl")     — "" when the build cannot download (offline flavor).
 *   @Named("canDownloadPacks")   — false for the offline flavor; true for withNetwork.
 */
package au.com.ausroads.di

import au.com.ausroads.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MapPackConfigModule {

    @Provides
    @Singleton
    @Named("mapPackBaseUrl")
    fun provideMapPackBaseUrl(): String = BuildConfig.MAP_PACK_BASE_URL

    @Provides
    @Singleton
    @Named("canDownloadPacks")
    fun provideCanDownloadPacks(): Boolean = BuildConfig.CAN_DOWNLOAD_PACKS
}
