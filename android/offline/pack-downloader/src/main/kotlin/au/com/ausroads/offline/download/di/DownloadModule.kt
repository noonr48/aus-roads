package au.com.ausroads.offline.download.di

import android.content.Context
import au.com.ausroads.offline.download.download.PackDownloader
import au.com.ausroads.offline.download.download.PackExtractor
import au.com.ausroads.offline.download.download.PackInstaller
import au.com.ausroads.offline.download.download.PackVerifier
import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.manifest.ManifestCache
import au.com.ausroads.offline.download.manifest.ManifestFetcher
import au.com.ausroads.offline.download.network.HttpClientProvider
import au.com.ausroads.offline.download.state.PackStateStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.datetime.Clock
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    @Provides
    @Singleton
    fun provideHttpClient(
        @Named("mapPackBaseUrl") baseUrl: String,
    ): HttpClient {
        // Permit the configured base URL's host so a custom/local endpoint works
        // through the privacy endpoint gate. Blank base (offline flavor) adds none.
        val extraHosts = baseUrl.takeIf { it.isNotBlank() }
            ?.let { runCatching { Url(it).host }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.let { setOf(it) }
            ?: emptySet()
        return HttpClientProvider.createHttpClient(extraAllowedHosts = extraHosts)
    }

    @Provides
    @Singleton
    fun providePackStateStore(
        @ApplicationContext context: Context,
    ): PackStateStore = PackStateStore(context, Clock.System)

    @Provides
    @Singleton
    fun provideManifestCache(
        packStateStore: PackStateStore,
    ): ManifestCache = ManifestCache(packStateStore)

    @Provides
    @Singleton
    fun provideManifestFetcher(
        client: HttpClient,
        manifestCache: ManifestCache,
        @Named("mapPackBaseUrl") baseUrl: String,
    ): ManifestFetcher = ManifestFetcher(client, manifestCache, baseUrl)

    @Provides
    @Singleton
    fun providePackDownloader(
        client: HttpClient,
    ): PackDownloader = PackDownloader(client)

    @Provides
    @Singleton
    fun providePackVerifier(): PackVerifier = PackVerifier()

    @Provides
    @Singleton
    fun providePackExtractor(): PackExtractor = PackExtractor()

    @Provides
    @Singleton
    fun providePackInstaller(
        packVerifier: PackVerifier,
        packStateStore: PackStateStore,
        evictionManager: EvictionManager,
    ): PackInstaller = PackInstaller(packVerifier, packStateStore, evictionManager, Clock.System)

    @Provides
    @Singleton
    fun provideEvictionManager(
        packStateStore: PackStateStore,
    ): EvictionManager = EvictionManager(packStateStore)
}
