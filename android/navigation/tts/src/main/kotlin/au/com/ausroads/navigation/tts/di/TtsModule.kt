package au.com.ausroads.navigation.tts.di

import au.com.ausroads.navigation.tts.NavigationTts
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object TtsModule {
    // NavigationTts uses @Inject constructor, so Hilt can create it directly.
    // This module exists for future configuration (e.g., language selection).
}
