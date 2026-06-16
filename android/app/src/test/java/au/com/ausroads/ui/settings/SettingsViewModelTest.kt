package au.com.ausroads.ui.settings

import app.cash.turbine.test
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepository: FakeSettingsRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSettingsRepository()
        viewModel = SettingsViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is default Settings`() = runTest {
        viewModel.settings.test {
            assertThat(awaitItem()).isEqualTo(Settings())
        }
    }

    @Test
    fun `setThemeMode updates the theme`() = runTest {
        viewModel.settings.test {
            assertThat(awaitItem()).isEqualTo(Settings())
            viewModel.setThemeMode(ThemeMode.Dark)
            assertThat(awaitItem().theme).isEqualTo(ThemeMode.Dark)
        }
    }

    @Test
    fun `setTrafficOverlayEnabled updates the setting`() = runTest {
        viewModel.settings.test {
            assertThat(awaitItem()).isEqualTo(Settings())
            viewModel.setTrafficOverlayEnabled(true)
            assertThat(awaitItem().liveTrafficEnabled).isTrue()
        }
    }

    @Test
    fun `setAvoidOptions persists the avoid preferences`() = runTest {
        viewModel.settings.test {
            assertThat(awaitItem()).isEqualTo(Settings())
            viewModel.setAvoidOptions(avoidTolls = true, avoidUnsealed = false, avoidFerries = true)
            val updated = awaitItem()
            assertThat(updated.avoidTolls).isTrue()
            assertThat(updated.avoidFerries).isTrue()
            assertThat(updated.avoidUnsealed).isFalse()
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val _settings = MutableStateFlow(Settings())
        override val settings: Flow<Settings> = _settings

        override suspend fun setTheme(mode: ThemeMode) {
            _settings.value = _settings.value.copy(theme = mode)
        }

        override suspend fun setShowAttributionOverlay(show: Boolean) {
            _settings.value = _settings.value.copy(showAttributionOverlay = show)
        }

        override suspend fun setLiveTrafficEnabled(enabled: Boolean) {
            _settings.value = _settings.value.copy(liveTrafficEnabled = enabled)
        }

        override suspend fun setTrafficSourceEnabled(sourceId: String, enabled: Boolean) {
            val current = _settings.value.enabledTrafficSources
            _settings.value = _settings.value.copy(
                enabledTrafficSources = if (enabled) current + sourceId else current - sourceId,
            )
        }

        override suspend fun setTtsEnabled(enabled: Boolean) {
            _settings.value = _settings.value.copy(ttsEnabled = enabled)
        }

        override suspend fun setCongestionOverlayEnabled(enabled: Boolean) {
            _settings.value = _settings.value.copy(congestionOverlayEnabled = enabled)
        }

        override suspend fun setNswTrafficApiKey(key: String) {
            _settings.value = _settings.value.copy(nswTrafficApiKey = key)
        }

        override suspend fun setVicTrafficApiKey(key: String) {
            _settings.value = _settings.value.copy(vicTrafficApiKey = key)
        }

        override suspend fun setAvoidOptions(
            avoidTolls: Boolean,
            avoidUnsealed: Boolean,
            avoidFerries: Boolean,
        ) {
            _settings.value = _settings.value.copy(
                avoidTolls = avoidTolls,
                avoidUnsealed = avoidUnsealed,
                avoidFerries = avoidFerries,
            )
        }
    }
}
