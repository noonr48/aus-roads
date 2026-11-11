package au.com.ausroads.ui.settings

import app.cash.turbine.test
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
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

    @Test
    fun `failed write exposes the affected setting without raw exception text`() = runTest {
        fakeRepository.failNext(IllegalStateException("internal datastore detail"))

        viewModel.setTtsEnabled(false)

        assertThat(viewModel.writeError.value).isEqualTo(
            SettingsWriteError(setting = SettingsField.TextToSpeech),
        )
        assertThat(fakeRepository.current.ttsEnabled).isTrue()
    }

    @Test
    fun `retry persists the exact failed mutation and clears the error`() = runTest {
        fakeRepository.failNext(IllegalStateException("write failed"))
        viewModel.setNswTrafficApiKey("replacement-key")
        assertThat(viewModel.writeError.value?.setting).isEqualTo(SettingsField.NswTrafficApiKey)

        viewModel.retryFailedWrite()

        assertThat(fakeRepository.current.nswTrafficApiKey).isEqualTo("replacement-key")
        assertThat(fakeRepository.writeAttempts).isEqualTo(2)
        assertThat(viewModel.writeError.value).isNull()
    }

    @Test
    fun `cancellation is rethrown and never becomes a retryable error`() = runTest {
        val cancellation = CancellationException("test cancellation")
        fakeRepository.failNext(cancellation)
        var caught: CancellationException? = null

        try {
            viewModel.persistMutation(SettingsMutation.Theme(ThemeMode.Dark))
        } catch (error: CancellationException) {
            caught = error
        }

        assertThat(caught).isSameInstanceAs(cancellation)
        assertThat(viewModel.writeError.value).isNull()
        assertThat(fakeRepository.current.theme).isEqualTo(ThemeMode.System)
    }

    @Test
    fun `all exposed setting mutations persist normally`() = runTest {
        viewModel.setThemeMode(ThemeMode.Light)
        viewModel.setTrafficOverlayEnabled(true)
        viewModel.setShowAttribution(false)
        viewModel.setTtsEnabled(false)
        viewModel.setCongestionOverlayEnabled(true)
        viewModel.setNswTrafficApiKey("nsw-key")
        viewModel.setVicTrafficApiKey("vic-key")
        viewModel.setAvoidOptions(
            avoidTolls = true,
            avoidUnsealed = true,
            avoidFerries = false,
        )

        assertThat(fakeRepository.current).isEqualTo(
            Settings(
                theme = ThemeMode.Light,
                showAttributionOverlay = false,
                liveTrafficEnabled = true,
                ttsEnabled = false,
                congestionOverlayEnabled = true,
                nswTrafficApiKey = "nsw-key",
                vicTrafficApiKey = "vic-key",
                avoidTolls = true,
                avoidUnsealed = true,
                avoidFerries = false,
            ),
        )
        assertThat(viewModel.writeError.value).isNull()
    }

    @Test
    fun `dismiss clears the error and removes its retry action`() = runTest {
        fakeRepository.failNext(IllegalStateException("write failed"))
        viewModel.setThemeMode(ThemeMode.Dark)
        assertThat(viewModel.writeError.value).isNotNull()

        viewModel.dismissWriteError()
        viewModel.retryFailedWrite()

        assertThat(viewModel.writeError.value).isNull()
        assertThat(fakeRepository.current.theme).isEqualTo(ThemeMode.System)
        assertThat(fakeRepository.writeAttempts).isEqualTo(1)
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val _settings = MutableStateFlow(Settings())
        override val settings: Flow<Settings> = _settings
        val current: Settings get() = _settings.value
        var writeAttempts: Int = 0
            private set
        private var nextFailure: Exception? = null

        fun failNext(error: Exception) {
            nextFailure = error
        }

        private fun beforeWrite() {
            writeAttempts++
            nextFailure?.let { error ->
                nextFailure = null
                throw error
            }
        }

        override suspend fun setTheme(mode: ThemeMode) {
            beforeWrite()
            _settings.value = _settings.value.copy(theme = mode)
        }

        override suspend fun setShowAttributionOverlay(show: Boolean) {
            beforeWrite()
            _settings.value = _settings.value.copy(showAttributionOverlay = show)
        }

        override suspend fun setLiveTrafficEnabled(enabled: Boolean) {
            beforeWrite()
            _settings.value = _settings.value.copy(liveTrafficEnabled = enabled)
        }

        override suspend fun setTrafficSourceEnabled(sourceId: String, enabled: Boolean) {
            beforeWrite()
            val current = _settings.value.enabledTrafficSources
            _settings.value = _settings.value.copy(
                enabledTrafficSources = if (enabled) current + sourceId else current - sourceId,
            )
        }

        override suspend fun setTtsEnabled(enabled: Boolean) {
            beforeWrite()
            _settings.value = _settings.value.copy(ttsEnabled = enabled)
        }

        override suspend fun setCongestionOverlayEnabled(enabled: Boolean) {
            beforeWrite()
            _settings.value = _settings.value.copy(congestionOverlayEnabled = enabled)
        }

        override suspend fun setNswTrafficApiKey(key: String) {
            beforeWrite()
            _settings.value = _settings.value.copy(nswTrafficApiKey = key)
        }

        override suspend fun setVicTrafficApiKey(key: String) {
            beforeWrite()
            _settings.value = _settings.value.copy(vicTrafficApiKey = key)
        }

        override suspend fun setAvoidOptions(
            avoidTolls: Boolean,
            avoidUnsealed: Boolean,
            avoidFerries: Boolean,
        ) {
            beforeWrite()
            _settings.value = _settings.value.copy(
                avoidTolls = avoidTolls,
                avoidUnsealed = avoidUnsealed,
                avoidFerries = avoidFerries,
            )
        }
    }
}
