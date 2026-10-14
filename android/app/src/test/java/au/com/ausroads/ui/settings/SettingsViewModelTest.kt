package au.com.ausroads.ui.settings

import app.cash.turbine.test
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.ThemeMode
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.PlacesSeam
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import java.io.IOException
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
    private val health = mockk<PlacesHealth>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeSettingsRepository()
        viewModel = SettingsViewModel(fakeRepository, health)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
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
    fun `failed preference write preserves confirmed settings without an uncaught exception`() = runTest {
        viewModel.settings.test {
            assertThat(awaitItem()).isEqualTo(Settings())
            fakeRepository.failWrite = true
            viewModel.setThemeMode(ThemeMode.Dark)
            runCurrent()
            assertThat(viewModel.settings.value).isEqualTo(Settings())
        }
    }

    @Test
    fun `failed read after a good value retains it without an uncaught exception`() = runTest {
        viewModel.settings.test {
            assertThat(awaitItem()).isEqualTo(Settings())
            fakeRepository.emit(Settings(theme = ThemeMode.Dark))
            assertThat(awaitItem().theme).isEqualTo(ThemeMode.Dark)
            fakeRepository.failRead = true
            fakeRepository.emit(Settings(theme = ThemeMode.Light))
            runCurrent()
            assertThat(viewModel.settings.value.theme).isEqualTo(ThemeMode.Dark)
        }
    }

    @Test
    fun `failed exact intent survives unrelated success and retries in the same coordinator`() = runTest {
        fakeRepository.failWrite = true
        viewModel.setThemeMode(ThemeMode.Dark)
        runCurrent()
        assertThat(viewModel.storeState.value.writeFailed).isTrue()
        verify(exactly = 1) { health.failure(PlacesSeam.SETTINGS_WRITE) }
        viewModel.setTtsEnabled(false)
        runCurrent()
        assertThat(viewModel.settings.value.ttsEnabled).isFalse()
        assertThat(viewModel.settings.value.theme).isEqualTo(ThemeMode.System)
        assertThat(viewModel.storeState.value.writeFailed).isTrue()
        fakeRepository.failWrite = false
        viewModel.retry()
        runCurrent()
        assertThat(viewModel.settings.value.theme).isEqualTo(ThemeMode.Dark)
        assertThat(viewModel.settings.value.ttsEnabled).isFalse()
        assertThat(viewModel.storeState.value.writeFailed).isFalse()
        assertThat(fakeRepository.themeWrites).isEqualTo(listOf(ThemeMode.Dark, ThemeMode.Dark))
        verify(atLeast = 1) { health.success(PlacesSeam.SETTINGS_WRITE) }
    }

    @Test
    fun `newer concern intent supersedes an old failed retry`() = runTest {
        fakeRepository.failWrite = true
        viewModel.setThemeMode(ThemeMode.Dark)
        runCurrent()
        fakeRepository.failWrite = false
        viewModel.setThemeMode(ThemeMode.Light)
        runCurrent()
        viewModel.retry()
        runCurrent()
        assertThat(viewModel.settings.value.theme).isEqualTo(ThemeMode.Light)
        assertThat(viewModel.storeState.value.writeFailed).isFalse()
        assertThat(fakeRepository.themeWrites).isEqualTo(listOf(ThemeMode.Dark, ThemeMode.Light))
    }

    @Test
    fun `read retry recovers the last good reader without writing defaults`() = runTest {
        fakeRepository.emit(Settings(theme = ThemeMode.Dark, ttsEnabled = false, avoidTolls = true))
        runCurrent()
        fakeRepository.failRead = true
        fakeRepository.emit(Settings(theme = ThemeMode.Light, ttsEnabled = false, avoidTolls = true))
        runCurrent()
        assertThat(viewModel.settings.value.theme).isEqualTo(ThemeMode.Dark)
        assertThat(viewModel.storeState.value.readFailed).isTrue()
        assertThat(viewModel.storeState.value.canEdit).isFalse()
        verify(exactly = 1) { health.failure(PlacesSeam.SETTINGS_READ) }
        fakeRepository.failRead = false
        viewModel.retry()
        runCurrent()
        assertThat(viewModel.settings.value.theme).isEqualTo(ThemeMode.Light)
        assertThat(viewModel.settings.value.ttsEnabled).isFalse()
        assertThat(viewModel.settings.value.avoidTolls).isTrue()
        assertThat(viewModel.storeState.value.canEdit).isTrue()
        assertThat(fakeRepository.themeWrites).isEmpty()
    }

    @Test
    fun `first read failure is not a default read and explicit retry replays only retained intent`() = runTest {
        viewModel.viewModelScope.cancel()
        fakeRepository.failRead = true
        viewModel = SettingsViewModel(fakeRepository, health)
        runCurrent()
        assertThat(viewModel.storeState.value.hasReadValue).isFalse()
        assertThat(viewModel.storeState.value.readFailed).isTrue()
        viewModel.setThemeMode(ThemeMode.Dark)
        assertThat(fakeRepository.themeWrites).isEmpty()
        fakeRepository.failRead = false
        viewModel.retry()
        runCurrent()
        assertThat(viewModel.storeState.value.hasReadValue).isTrue()
        assertThat(viewModel.storeState.value.writeFailed).isFalse()
        assertThat(viewModel.settings.value.theme).isEqualTo(ThemeMode.Dark)
        assertThat(fakeRepository.themeWrites).isEqualTo(listOf(ThemeMode.Dark))
    }

    @Test
    fun `held API write keeps latest A B A draft and serializes stale requests`() = runTest {
        val gate = CompletableDeferred<Unit>()
        fakeRepository.keyGate = gate
        viewModel.setNswTrafficApiKey("synthetic-A")
        runCurrent()
        assertThat(viewModel.storeState.value.isSaving).isTrue()
        assertThat(viewModel.settings.value.nswTrafficApiKey).isEmpty()
        viewModel.setNswTrafficApiKey("synthetic-AB")
        viewModel.setNswTrafficApiKey("synthetic-A")
        runCurrent()
        assertThat(viewModel.nswApiDraft.value).isEqualTo("synthetic-A")
        gate.complete(Unit)
        runCurrent()
        assertThat(viewModel.settings.value.nswTrafficApiKey).isEqualTo("synthetic-A")
        assertThat(fakeRepository.keyWrites).isEqualTo(listOf("synthetic-A", "synthetic-A"))
        assertThat(viewModel.storeState.value.pendingWrites).isEqualTo(0)
    }

    @Test
    fun `canceling a held write never creates failure or publishes settings`() = runTest {
        val gate = CompletableDeferred<Unit>()
        fakeRepository.themeGate = gate
        viewModel.setThemeMode(ThemeMode.Dark)
        runCurrent()
        viewModel.viewModelScope.cancel()
        gate.complete(Unit)
        runCurrent()
        assertThat(viewModel.settings.value.theme).isEqualTo(ThemeMode.System)
        assertThat(viewModel.storeState.value.pendingWrites).isEqualTo(0)
        assertThat(viewModel.storeState.value.writeFailed).isFalse()
        verify(exactly = 0) { health.failure(PlacesSeam.SETTINGS_WRITE) }
        assertThat(fakeRepository.activeReaders).isEqualTo(0)
    }

    @Test
    fun `failed API intent retries the exact latest draft without erasing other concerns`() = runTest {
        fakeRepository.failKeyWrite = true
        viewModel.setNswTrafficApiKey("synthetic-private-key")
        runCurrent()
        assertThat(viewModel.nswApiDraft.value).isEqualTo("synthetic-private-key")
        assertThat(viewModel.settings.value.nswTrafficApiKey).isEmpty()
        viewModel.setSpeedCamerasEnabled(false)
        runCurrent()
        assertThat(viewModel.storeState.value.writeFailed).isTrue()
        fakeRepository.failKeyWrite = false
        viewModel.retry()
        runCurrent()
        assertThat(viewModel.settings.value.nswTrafficApiKey).isEqualTo("synthetic-private-key")
        assertThat(viewModel.settings.value.speedCamerasEnabled).isFalse()
        assertThat(viewModel.storeState.value.writeFailed).isFalse()
        verify(exactly = 1) { health.failure(PlacesSeam.SETTINGS_WRITE) }
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val _settings = MutableStateFlow(Settings())
        var failWrite = false
        var failRead = false
        var failKeyWrite = false
        var themeGate: CompletableDeferred<Unit>? = null
        var keyGate: CompletableDeferred<Unit>? = null
        var activeReaders = 0
        val themeWrites = mutableListOf<ThemeMode>()
        val keyWrites = mutableListOf<String>()
        override val settings: Flow<Settings> = flow {
            activeReaders++
            try {
                emitAll(_settings.map {
                    if (failRead) throw IOException("synthetic private settings read")
                    it
                })
            } finally { activeReaders-- }
        }

        fun emit(settings: Settings) { _settings.value = settings }

        override suspend fun setTheme(mode: ThemeMode) {
            themeWrites += mode
            themeGate?.await()
            if (failWrite) throw IOException("synthetic private settings write")
            _settings.value = _settings.value.copy(theme = mode)
        }

        override suspend fun setShowAttributionOverlay(show: Boolean) {
            _settings.value = _settings.value.copy(showAttributionOverlay = show)
        }

        override suspend fun setLiveTrafficEnabled(enabled: Boolean) {
            _settings.value = _settings.value.copy(liveTrafficEnabled = enabled)
        }

        override suspend fun setSpeedCamerasEnabled(enabled: Boolean) {
            _settings.value = _settings.value.copy(speedCamerasEnabled = enabled)
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
            keyWrites += key
            keyGate?.await()
            if (failKeyWrite) throw IOException("synthetic private settings API write")
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
