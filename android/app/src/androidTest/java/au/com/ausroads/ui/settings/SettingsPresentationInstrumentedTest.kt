package au.com.ausroads.ui.settings

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import au.com.ausroads.BuildConfig
import au.com.ausroads.MainActivity
import au.com.ausroads.data.settings.DataStoreSettingsRepository
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.R
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.SettingsRepository
import au.com.ausroads.data.settings.ThemeMode
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual native Compose controls; callbacks and health files are isolated, never owner pack/data. */
@RunWith(AndroidJUnit4::class)
class SettingsPresentationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var viewModel: SettingsViewModel? = null
    private var fixture: File? = null

    @After fun dispose() {
        compose.runOnIdle { viewModel?.viewModelScope?.cancel() }
        fixture?.deleteRecursively()
    }

    @Test fun downloadProgressCancelAndWorkerRetryCallExactlyTheirOwners() {
        var downloads = 0
        var cancels = 0
        var retries = 0
        val progress = mutableStateOf<DownloadProgress?>(null)
        val workerError = mutableStateOf<String?>(null)
        compose.setContent {
            MaterialTheme {
                MapPackSection(null, progress.value, false, workerError.value, workerError.value, null, false, false,
                    { downloads++ }, { cancels++ }, { retries++ }, {}, {}, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.download_map_pack)).performClick()
        compose.runOnIdle {
            assertThat(downloads).isEqualTo(1)
            progress.value = DownloadProgress(DownloadProgress.Phase.DOWNLOADING, 64, 128)
        }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertThat(cancels).isEqualTo(1)
            progress.value = null
            workerError.value = "Synthetic verification failure"
        }
        compose.onNodeWithText("Synthetic verification failure").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.map_pack_action_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.map_pack_worker_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.map_pack_retry)).performClick()
        compose.runOnIdle {
            assertThat(retries).isEqualTo(1)
            assertThat(downloads).isEqualTo(1)
            workerError.value = context.getString(R.string.download_failed_checksum)
        }
        compose.onNodeWithText(context.getString(R.string.download_failed_checksum)).assertIsDisplayed()
    }

    @Test fun installedRemovalIsOnlyAnIntentUntilConfirmationAndCancelIsSafe() {
        val pack = InstalledPack(version = "test", regionCode = "AU-SA",
            installedAt = Instant.parse("2026-06-01T00:00:00Z"), totalSizeBytes = 100,
            tilesPath = "tiles.mbtiles", manifestSha256 = "synthetic")
        val confirm = mutableStateOf(false)
        var removals = 0
        compose.setContent {
            MaterialTheme {
                MapPackSection(pack, null, false, null, null, null, confirm.value, false,
                    {}, {}, {}, { confirm.value = true }, { confirm.value = false },
                    { removals++; confirm.value = false })
            }
        }
        compose.onNodeWithText(context.getString(R.string.settings_map_pack_uninstall)).performClick()
        compose.onNodeWithText(context.getString(R.string.map_pack_uninstall_title)).assertIsDisplayed()
        compose.runOnIdle { assertThat(removals).isEqualTo(0) }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertThat(removals).isEqualTo(0) }
        compose.onNodeWithText(context.getString(R.string.settings_map_pack_uninstall)).performClick()
        compose.onAllNodesWithText(context.getString(R.string.settings_map_pack_uninstall))
            .onLast().performClick()
        compose.runOnIdle { assertThat(removals).isEqualTo(1) }
    }

    @Test fun failedStoreShowsExplicitRetryAndDisablesItWhileRecoveryIsInFlight() {
        var retries = 0
        val state = mutableStateOf(SettingsStoreState(readFailed = true))
        compose.setContent { MaterialTheme { SettingsStoreNotice(state.value) { retries++ } } }
        compose.onNodeWithText(context.getString(R.string.settings_store_read_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_store_retry)).performClick()
        compose.runOnIdle {
            assertThat(retries).isEqualTo(1)
            state.value = state.value.copy(retryingRead = true)
        }
        compose.onNodeWithText(context.getString(R.string.settings_store_retry)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.settings_store_loading)).assertIsDisplayed()
        compose.runOnIdle { state.value = SettingsStoreState(hasReadValue = true, writeFailed = true, pendingWrites = 1) }
        compose.onNodeWithText(context.getString(R.string.settings_store_write_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_store_saving)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_store_retry)).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(pendingWrites = 0) }
        compose.onNodeWithText(context.getString(R.string.settings_store_saving)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_store_retry)).assertIsEnabled()
    }

    @Test fun actualApiFieldKeepsEveryTypedCharacterWhileItsSaveIsHeld() {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "settings-presentation-").toFile()
        fixture = root
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = root
        }
        val repository = HeldRepository()
        lateinit var model: SettingsViewModel
        compose.runOnIdle { model = SettingsViewModel(repository, PlacesHealth(isolated)); viewModel = model }
        compose.setContent {
            val confirmed by model.settings.collectAsState()
            val draft by model.nswApiDraft.collectAsState()
            MaterialTheme {
                ApiKeyField("Synthetic API key", draft ?: confirmed.nswTrafficApiKey,
                    model::setNswTrafficApiKey, "Key")
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("a")
        compose.onNode(hasSetTextAction()).performTextInput("b")
        compose.onNode(hasSetTextAction()).performTextInput("c")
        compose.runOnIdle {
            assertThat(model.nswApiDraft.value).isEqualTo("abc")
            assertThat(model.settings.value.nswTrafficApiKey).isEmpty()
            repository.gate.complete(Unit)
        }
        compose.waitUntil(5_000) { model.settings.value.nswTrafficApiKey == "abc" }
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("xy")
        compose.waitUntil(5_000) { model.settings.value.nswTrafficApiKey == "xy" }
        compose.runOnIdle { assertThat(model.nswApiDraft.value).isEqualTo("xy") }
    }

    private class HeldRepository : SettingsRepository {
        override val settings = MutableStateFlow(Settings())
        val gate = CompletableDeferred<Unit>()
        override suspend fun setNswTrafficApiKey(key: String) {
            gate.await()
            settings.value = settings.value.copy(nswTrafficApiKey = key)
        }
        override suspend fun setTheme(mode: ThemeMode) { settings.value = settings.value.copy(theme = mode) }
        override suspend fun setShowAttributionOverlay(show: Boolean) = Unit
        override suspend fun setLiveTrafficEnabled(enabled: Boolean) = Unit
        override suspend fun setSpeedCamerasEnabled(enabled: Boolean) = Unit
        override suspend fun setTrafficSourceEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun setTtsEnabled(enabled: Boolean) = Unit
        override suspend fun setCongestionOverlayEnabled(enabled: Boolean) = Unit
        override suspend fun setVicTrafficApiKey(key: String) = Unit
        override suspend fun setAvoidOptions(avoidTolls: Boolean, avoidUnsealed: Boolean, avoidFerries: Boolean) = Unit
    }
}

/** MainActivity-owned Hilt controls, real DataStore writes, and restored emulator-only fault seams. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityPresentationInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: SettingsViewModel
    private lateinit var repository: DataStoreSettingsRepository
    private lateinit var originalStore: DataStore<Preferences>
    private lateinit var originalFlow: Flow<Settings>
    private var originalPreferences: Preferences? = null
    private val releases = mutableListOf<CompletableDeferred<Unit>>()
    private var writeProbe: SettingsWriteProbe? = null
    private val storeField = DataStoreSettingsRepository::class.java.getDeclaredField("store")
        .apply { isAccessible = true }
    private val flowField = DataStoreSettingsRepository::class.java.getDeclaredField("settings")
        .apply { isAccessible = true }

    @Before
    fun disposableActivityOnly(): Unit = runBlocking {
        check(Build.MODEL.startsWith("sdk_gphone")) { "Never fault or mutate owner phone settings" }
        launch()
        repository = SettingsViewModel::class.java.getDeclaredField("settingsRepository")
            .apply { isAccessible = true }.get(model) as DataStoreSettingsRepository
        @Suppress("UNCHECKED_CAST")
        val store = storeField.get(repository) as DataStore<Preferences>
        originalStore = store
        originalFlow = repository.settings
        originalPreferences = originalStore.data.first()
        scenario!!.close()
        scenario = null
    }

    @After
    fun restoreWholeNativeStore(): Unit = runBlocking {
        // JUnit retains the body failure. Attempt every restoration even if an
        // earlier cleanup fails, and finish canceled writers before restoring.
        val failures = mutableListOf<Throwable>()
        suspend fun restore(action: suspend () -> Unit) {
            try { action() } catch (failure: Throwable) { failures += failure }
        }
        restore { releases.forEach { it.complete(Unit) } }
        restore { scenario?.close() }
        restore {
            if (::model.isInitialized) {
                model.viewModelScope.cancel()
                withTimeout(10_000) { model.viewModelScope.coroutineContext[Job]?.join() }
            }
        }
        restore { if (::originalStore.isInitialized) storeField.set(repository, originalStore) }
        restore { if (::originalFlow.isInitialized) flowField.set(repository, originalFlow) }
        restore {
            originalPreferences?.let { original ->
                originalStore.updateData { original }
                check(originalStore.data.first() == original) { "Whole native Settings preferences not restored" }
                writeProbe?.record("restored", JSONObject().put("equals_original", true))
            }
        }
        restore { writeProbe?.close() }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
        Unit
    }

    @Test
    fun settingsSwitchesExposeBoundNamesRolesAndPersistedCheckedStates(): Unit = runBlocking {
        val probe = SettingsWriteProbe(originalStore, context).also { writeProbe = it }
        storeField.set(repository, probe)
        probe.record("before-fixture", JSONObject().put("equals_original", originalStore.data.first() == originalPreferences))
        // Calibrate on the actual delegate/transaction, not Held.entered or a fake
        // receipt. Both controls preserve every original preference.
        val synthetic = IllegalStateException("Synthetic Settings transaction witness failure")
        check(runCatching { probe.updateData { throw synthetic } }.exceptionOrNull() === synthetic)
        check(probe.calls.get() == 1 && probe.transforms.get() == 1 && probe.returns.get() == 0 && probe.throws.get() == 1)
        check(probe.updateData { it } == originalPreferences)
        check(probe.calls.get() == 2 && probe.transforms.get() == 2 && probe.returns.get() == 1 && probe.throws.get() == 1)
        check(originalStore.data.first() == originalPreferences)
        probe.flush()
        repository.setSpeedCamerasEnabled(true)
        repository.setTtsEnabled(false)
        repository.setShowAttributionOverlay(true)
        repository.setLiveTrafficEnabled(false)
        val before = originalFlow.first()
        launch()
        compose.waitUntil(10_000) { model.storeState.value.canEdit }
        openSettings()
        val controls = listOf(
            R.string.settings_speed_cameras to true,
            R.string.settings_tts to false,
            R.string.settings_show_attribution to true,
        ) + if (BuildConfig.CAN_DOWNLOAD_PACKS) listOf(R.string.settings_traffic_overlay to false)
            else emptyList()
        controls.forEach { (resource, checked) ->
            val label = context.getString(resource)
            compose.onAllNodesWithContentDescription(label).assertCountEquals(1)
            val control = compose.onNodeWithContentDescription(label).performScrollTo()
                .assertIsDisplayed().assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            if (checked) control.assertIsOn() else control.assertIsOff()
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(controls.size)
        val cameras = context.getString(R.string.settings_speed_cameras)
        val camera = compose.onNodeWithContentDescription(cameras).performScrollTo()
        val node = camera.fetchSemanticsNode()
        val clipped = node.boundsInRoot
        val position = node.positionInRoot
        val size = node.size
        probe.record("before-original-click", JSONObject().put("node_id", node.id)
            .put("disabled", node.config.contains(SemanticsProperties.Disabled)).put("placed", node.layoutInfo.isPlaced)
            .put("clipped_left", clipped.left.toDouble()).put("clipped_top", clipped.top.toDouble())
            .put("clipped_right", clipped.right.toDouble()).put("clipped_bottom", clipped.bottom.toDouble())
            .put("unclipped_left", position.x.toDouble()).put("unclipped_top", position.y.toDouble())
            .put("unclipped_width", size.width).put("unclipped_height", size.height)
            .put("window_bottom", node.boundsInWindow.bottom.toDouble()))
        switchState("before-original-click-state")
        probe.flush()
        // Keep the original root-View action and real 10s predicate: no retap,
        // post-UP Compose pumping, semantic setter or increased deadline here.
        try {
            awaitOriginalChange(before, { !it.speedCamerasEnabled }) {
                camera.performClick()
                switchState("after-original-click")
            }
        } catch (failure: Throwable) {
            // State/trace precede capture and teardown; these do not call
            // Compose waitForIdle (which would be a clock intervention).
            try {
                switchState("original-camera-await-failed")
                probe.flush()
                screenshot("settings-native-switch-timeout", settle = false)
            } catch (diagnostic: Throwable) { if (diagnostic !== failure) failure.addSuppressed(diagnostic) }
            throw failure
        }
        compose.waitUntil(10_000) { !model.settings.value.speedCamerasEnabled && !model.storeState.value.isSaving }
        compose.onNodeWithContentDescription(cameras).assertIsOff().assertIsEnabled()
        assertThat(originalFlow.first()).isEqualTo(before.copy(speedCamerasEnabled = false))
        switchState("original-camera-persisted")
        probe.flush()
        screenshot("settings-native-controls")
    }

    @Test
    fun heldSettingsReadAndWriteShowTruthfulStatusAndDisableActivityEdits(): Unit = runBlocking {
        val before = originalFlow.first()
        val readRelease = CompletableDeferred<Unit>().also { releases += it }
        flowField.set(repository, flow { readRelease.await(); emitAll(originalFlow) })
        launch()
        openSettings()
        compose.onNodeWithText(context.getString(R.string.settings_store_loading)).assertIsDisplayed()
        listOf(R.string.settings_theme_system, R.string.settings_theme_light, R.string.settings_theme_dark)
            .forEach { resource ->
                compose.onNodeWithText(context.getString(resource)).assertIsNotEnabled().assertIsNotSelected()
            }
        compose.onNodeWithContentDescription(context.getString(R.string.settings_speed_cameras)).assertDoesNotExist()
        assertThat(model.storeState.value.hasReadValue).isFalse()
        assertThat(originalStore.data.first()).isEqualTo(originalPreferences)
        screenshot("settings-native-loading")
        readRelease.complete(Unit)
        compose.waitUntil(10_000) { model.storeState.value.canEdit }
        assertThat(model.settings.value).isEqualTo(before)
        val held = HeldActivitySettingsStore(originalStore)
        releases += held.release
        storeField.set(repository, held)
        val cameras = context.getString(R.string.settings_speed_cameras)
        compose.onNodeWithContentDescription(cameras).performScrollTo().performClick()
        withTimeout(5_000) { held.entered.await() }
        compose.waitUntil(10_000) { model.storeState.value.isSaving }
        compose.onNodeWithContentDescription(cameras).assertIsNotEnabled()
        if (before.speedCamerasEnabled) compose.onNodeWithContentDescription(cameras).assertIsOn()
        else compose.onNodeWithContentDescription(cameras).assertIsOff()
        compose.onNodeWithContentDescription(context.getString(R.string.settings_tts)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.settings_theme_dark)).assertIsNotEnabled()
        assertThat(originalFlow.first()).isEqualTo(before)
        compose.onNodeWithText(context.getString(R.string.settings_store_saving))
            .performScrollTo().assertIsDisplayed()
        screenshot("settings-native-saving")
        awaitOriginalChange(before, { it.speedCamerasEnabled != before.speedCamerasEnabled }) {
            held.release.complete(Unit)
        }
        compose.waitUntil(10_000) { !model.storeState.value.isSaving }
        compose.onNodeWithText(context.getString(R.string.settings_store_saving)).assertDoesNotExist()
        compose.onNodeWithContentDescription(cameras).performScrollTo().assertIsEnabled()
        assertThat(originalFlow.first()).isEqualTo(before.copy(speedCamerasEnabled = !before.speedCamerasEnabled))
    }

    @Test
    fun activityApiEditorRetainsTypedDraftDuringSaveOrIsAbsentOffline(): Unit = runBlocking {
        if (BuildConfig.CAN_DOWNLOAD_PACKS) repository.setNswTrafficApiKey("")
        val before = originalFlow.first()
        launch()
        compose.waitUntil(10_000) { model.storeState.value.canEdit }
        openSettings()
        if (!BuildConfig.CAN_DOWNLOAD_PACKS) {
            compose.onNodeWithText(context.getString(R.string.settings_nsw_api_key)).assertDoesNotExist()
            assertThat(originalFlow.first()).isEqualTo(before)
            return@runBlocking
        }
        val held = HeldActivitySettingsStore(originalStore)
        releases += held.release
        storeField.set(repository, held)
        val field = hasSetTextAction() and hasText(context.getString(R.string.settings_nsw_api_key))
        compose.onNode(field).performScrollTo().performTextInput("a")
        withTimeout(5_000) { held.entered.await() }
        compose.onNode(field).assertIsEnabled().performTextInput("b")
        compose.onNode(field).performTextInput("c")
        compose.runOnIdle {
            assertThat(model.nswApiDraft.value).isEqualTo("abc")
            assertThat(model.settings.value.nswTrafficApiKey).isEmpty()
            assertThat(model.storeState.value.isSaving).isTrue()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.settings_speed_cameras)).assertIsNotEnabled()
        assertThat(originalFlow.first()).isEqualTo(before)
        screenshot("settings-native-api-saving")
        awaitOriginalChange(before, { it.nswTrafficApiKey == "abc" }) {
            held.release.complete(Unit)
        }
        compose.waitUntil(10_000) { model.settings.value.nswTrafficApiKey == "abc" && !model.storeState.value.isSaving }
        val savedAbc = originalFlow.first()
        awaitOriginalChange(savedAbc, { it.nswTrafficApiKey == "xy" }) {
            compose.onNode(field).performTextReplacement("xy")
        }
        compose.waitUntil(10_000) { model.settings.value.nswTrafficApiKey == "xy" && !model.storeState.value.isSaving }
        assertThat(model.nswApiDraft.value).isEqualTo("xy")
        assertThat(originalFlow.first()).isEqualTo(before.copy(nswTrafficApiKey = "xy"))
    }

    @Test
    fun flavorPrivacyCopyAndUnavailableControlsMatchRealActivityCapabilities() {
        launch()
        compose.waitUntil(10_000) { model.storeState.value.canEdit }
        openSettings()
        val expected = if (BuildConfig.CAN_DOWNLOAD_PACKS) R.string.about_privacy_network else R.string.about_privacy_offline
        val other = if (BuildConfig.CAN_DOWNLOAD_PACKS) R.string.about_privacy_offline else R.string.about_privacy_network
        if (!BuildConfig.CAN_DOWNLOAD_PACKS) {
            compose.onNodeWithText(context.getString(R.string.settings_traffic_overlay)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.settings_api_keys)).assertDoesNotExist()
        } else {
            compose.onNodeWithContentDescription(context.getString(R.string.settings_traffic_overlay)).assertExists()
        }
        compose.onNodeWithText(context.getString(expected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(other)).assertDoesNotExist()
        screenshot(if (BuildConfig.CAN_DOWNLOAD_PACKS) "settings-privacy-network" else "settings-privacy-offline")
        compose.onNodeWithText(context.getString(R.string.settings_view_attribution)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.about_privacy_posture)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.about_privacy_full)).performScrollTo().assertIsDisplayed()
    }

    /**
     * DataStore1.1.1 can stamp a dirty initial read with an in-flight write's
     * version, then filter out that write's equal-version committed emission.
     * Prime this ORIGINAL reader before the action, with an actual first-value
     * handshake (launch/UNDISPATCHED alone is not readiness). After the unchanged
     * 10s changed-value predicate, callers still check completed saving, native
     * controls and an independent fresh whole-settings read. No app/store fix.
     */
    private suspend fun awaitOriginalChange(
        before: Settings,
        matches: (Settings) -> Boolean,
        action: () -> Unit,
    ): Settings = coroutineScope {
        check(!matches(before)) { "Original Settings change predicate already matches fixture" }
        val armed = CompletableDeferred<Unit>()
        val changed = async(Dispatchers.IO) {
            originalFlow.onEach { value ->
                writeProbe?.record("original-flow-emission", JSONObject().put("camera", value.speedCamerasEnabled))
                if (!armed.isCompleted) {
                    check(value == before) { "Original Settings observer initial value differs from fixture" }
                    writeProbe?.record("original-observer-armed", JSONObject().put("camera", value.speedCamerasEnabled))
                    armed.complete(Unit)
                }
            }.first { matches(it) }
        }
        try {
            withTimeout(10_000) { armed.await() }
            action()
            withTimeout(10_000) { changed.await() }
        } finally {
            withContext(NonCancellable) { withTimeout(10_000) { changed.cancelAndJoin() } }
        }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = ViewModelProvider(it)[SettingsViewModel::class.java] }
    }

    private fun openSettings() {
        compose.onAllNodesWithText(context.getString(R.string.nav_settings)).onLast().performClick()
    }

    private fun switchState(stage: String) {
        scenario!!.onActivity { activity ->
            val writer = SettingsViewModel::class.java.getDeclaredField("writer")
                .apply { isAccessible = true }.get(model)
            val latest = writer.javaClass.getDeclaredField("latest").apply { isAccessible = true }
                .get(writer) as Map<*, *>
            val state = model.storeState.value
            val decor = activity.window.decorView
            val keyboard = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: -1
            writeProbe!!.record(stage, JSONObject().put("model_id", System.identityHashCode(model))
                .put("camera", model.settings.value.speedCamerasEnabled).put("can_edit", state.canEdit)
                .put("pending_writes", state.pendingWrites).put("read_failed", state.readFailed).put("write_failed", state.writeFailed)
                .put("probe_bound", storeField.get(repository) === writeProbe).put("original_flow_bound", repository.settings === originalFlow)
                .put("camera_intent", latest.entries.firstOrNull { (it.key as? Enum<*>)?.name == "CAMERAS" }?.value ?: JSONObject.NULL)
                .put("tts_intent", latest.entries.firstOrNull { (it.key as? Enum<*>)?.name == "TTS" }?.value ?: JSONObject.NULL)
                .put("keyboard_height", keyboard).put("available_bottom", decor.height - keyboard)
                .put("activity_focus", decor.hasWindowFocus()))
        }
    }

    /** TEST-only, redacted live sink; no file I/O or held gate on the UI/store path. */
    private class SettingsWriteProbe(private val delegate: DataStore<Preferences>, context: Context) : DataStore<Preferences> {
        override val data = delegate.data
        val calls = AtomicInteger()
        val transforms = AtomicInteger()
        val returns = AtomicInteger()
        val throws = AtomicInteger()
        private val sink = Executors.newSingleThreadExecutor()
        private val sinkFailure = AtomicReference<Throwable?>()
        private val file = File(context.cacheDir, "settings-switch-${android.os.Process.myPid()}-${SystemClock.uptimeMillis()}.jsonl")
        private val cameraKey = booleanPreferencesKey("speed_cameras")

        fun record(stage: String, fields: JSONObject = JSONObject()) {
            val row = JSONObject().put("schema", 1).put("case", "settingsSwitchesExposeBoundNamesRolesAndPersistedCheckedStates")
                .put("stage", stage).put("uptime_ms", SystemClock.uptimeMillis())
                .put("calls", calls.get()).put("transforms", transforms.get()).put("returns", returns.get()).put("throws", throws.get())
                .put("on_main", android.os.Looper.myLooper() === android.os.Looper.getMainLooper())
            fields.keys().forEach { key -> row.put(key, fields.get(key)) }
            val bytes = (row.toString() + "\n").toByteArray(Charsets.UTF_8)
            sink.submit {
                try {
                    java.io.FileOutputStream(file, true).use { output -> output.write(bytes); output.fd.sync() }
                } catch (failure: Throwable) { sinkFailure.compareAndSet(null, failure) }
            }
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val call = calls.incrementAndGet()
            record("write-call", JSONObject().put("call", call))
            try {
                return delegate.updateData { before ->
                    transforms.incrementAndGet()
                    record("native-transform-in", JSONObject().put("call", call).put("camera", before[cameraKey] ?: JSONObject.NULL))
                    transform(before).also { after ->
                        record("native-transform-out", JSONObject().put("call", call).put("camera", after[cameraKey] ?: JSONObject.NULL))
                    }
                }.also { after ->
                    returns.incrementAndGet()
                    record("native-return", JSONObject().put("call", call).put("camera", after[cameraKey] ?: JSONObject.NULL))
                }
            } catch (failure: Throwable) {
                throws.incrementAndGet()
                record("native-throw", JSONObject().put("call", call).put("cancelled", failure is kotlinx.coroutines.CancellationException))
                throw failure
            }
        }
        fun flush() {
            sink.submit {}.get(5, TimeUnit.SECONDS)
            check(sinkFailure.get() == null) { "Settings native trace sink failed" }
        }
        fun close() {
            try { flush() } finally {
                sink.shutdown()
                check(sink.awaitTermination(5, TimeUnit.SECONDS)) { "Settings trace thread not stopped" }
            }
        }
    }

    private fun screenshot(name: String, settle: Boolean = true) {
        if (settle) compose.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class HeldActivitySettingsStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override val data = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            entered.complete(Unit)
            release.await()
            return delegate.updateData(transform)
        }
    }
}
