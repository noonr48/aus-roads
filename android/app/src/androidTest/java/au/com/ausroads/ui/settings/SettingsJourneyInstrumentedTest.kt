package au.com.ausroads.ui.settings

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.MainActivity
import au.com.ausroads.R
import au.com.ausroads.data.settings.DataStoreSettingsRepository
import au.com.ausroads.data.settings.Settings
import au.com.ausroads.data.settings.ThemeMode
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity/Hilt/Compose/DataStore and private alarm; faults are emulator-only seams. */
@RunWith(AndroidJUnit4::class)
class SettingsJourneyInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repository: DataStoreSettingsRepository
    private lateinit var original: Settings
    private lateinit var originalFlow: Flow<Settings>
    private lateinit var originalStore: DataStore<Preferences>
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var model: SettingsViewModel
    private val storeField = DataStoreSettingsRepository::class.java.getDeclaredField("store")
        .apply { isAccessible = true }
    private val flowField = DataStoreSettingsRepository::class.java.getDeclaredField("settings")
        .apply { isAccessible = true }

    @Before fun onlyOwnGuest() = runBlocking {
        check(Build.MODEL.startsWith("sdk_gphone")) { "Never fault or mutate owner phone preferences" }
        // androidTest entry points are not in the production Hilt component. Obtain
        // its actual repository through a short real Activity bootstrap, not a fake graph.
        launch()
        repository = SettingsViewModel::class.java.getDeclaredField("settingsRepository")
            .apply { isAccessible = true }.get(model) as DataStoreSettingsRepository
        originalFlow = repository.settings
        original = originalFlow.first()
        @Suppress("UNCHECKED_CAST")
        val store = storeField.get(repository) as DataStore<Preferences>
        originalStore = store
        scenario!!.close()
        scenario = null
    }

    @After fun restoreExactSeams() = runBlocking {
        scenario?.close()
        if (::originalStore.isInitialized) storeField.set(repository, originalStore)
        if (::originalFlow.isInitialized) flowField.set(repository, originalFlow)
        if (::original.isInitialized) repository.setTheme(original.theme)
    }

    @Test fun realSettingsWriteFailureAndRetryHealTheActivityOwnerAndActualStore() = runBlocking {
        val fault = WriteFaultStore(originalStore)
        storeField.set(repository, fault)
        launch()
        compose.waitUntil(10_000) { model.storeState.value.canEdit }
        openSettings()
        // Private build really omits controls that require unavailable INTERNET/provider access.
        compose.onNodeWithText(context.getString(R.string.settings_traffic_overlay)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_api_keys)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.settings_offline_coverage)).assertIsDisplayed()
        val requested = if (original.theme == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark
        val label = if (requested == ThemeMode.Dark) R.string.settings_theme_dark else R.string.settings_theme_light
        val witness = File(context.noBackupFilesDir, "places-health.json")
        val beforeFailures = failures(witness, "SETTINGS_WRITE")
        compose.onNodeWithText(context.getString(label)).performClick()
        compose.waitUntil(10_000) { model.storeState.value.writeFailed }
        assertThat(model.settings.value).isEqualTo(original)
        assertThat(originalFlow.first()).isEqualTo(original)
        compose.onNodeWithText(context.getString(R.string.settings_store_write_error)).assertIsDisplayed()
        compose.waitUntil(10_000) { failures(witness, "SETTINGS_WRITE") > beforeFailures }
        assertThat(JSONObject(witness.readText()).getJSONObject("SETTINGS_WRITE").getString("last_error"))
            .isEqualTo("seam_failed")
        fault.fail = false
        val retriedAt = System.currentTimeMillis()
        compose.onNodeWithText(context.getString(R.string.settings_store_retry)).performClick()
        compose.waitUntil(10_000) {
            model.settings.value.theme == requested && !model.storeState.value.writeFailed &&
                !model.storeState.value.isSaving
        }
        // These are the SAME Activity coordinator as theme/guidance, not merely another reader.
        assertThat(model.settings.value).isEqualTo(original.copy(theme = requested))
        assertThat(originalFlow.first()).isEqualTo(original.copy(theme = requested))
        compose.onNodeWithText(context.getString(R.string.settings_store_write_error)).assertDoesNotExist()
        compose.waitUntil(10_000) {
            JSONObject(witness.readText()).getJSONObject("SETTINGS_WRITE").optLong("last_success_ms") >= retriedAt
        }
    }

    @Test fun unreadableSettingsDisableEditingAndSameScreenRetryNeverWritesDefaults() = runBlocking {
        val failRead = AtomicBoolean(true)
        flowField.set(repository, flow {
            if (failRead.get()) throw IOException("synthetic private read failure")
            emitAll(originalFlow)
        })
        launch()
        compose.waitUntil(10_000) { model.storeState.value.readFailed }
        assertThat(model.storeState.value.hasReadValue).isFalse()
        openSettings()
        compose.onNodeWithText(context.getString(R.string.settings_store_read_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_theme_dark)).assertIsNotEnabled()
        assertThat(originalFlow.first()).isEqualTo(original)
        val witness = File(context.noBackupFilesDir, "places-health.json")
        compose.waitUntil(10_000) { failures(witness, "SETTINGS_READ") > 0 }
        failRead.set(false)
        compose.onNodeWithText(context.getString(R.string.settings_store_retry)).performClick()
        compose.waitUntil(10_000) { model.storeState.value.canEdit }
        assertThat(model.settings.value).isEqualTo(original)
        assertThat(originalFlow.first()).isEqualTo(original)
        compose.onNodeWithText(context.getString(R.string.settings_store_read_error)).assertDoesNotExist()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = ViewModelProvider(it)[SettingsViewModel::class.java] }
    }

    private fun openSettings() {
        compose.onAllNodesWithText(context.getString(R.string.nav_settings)).onLast().performClick()
    }

    private fun failures(file: File, seam: String): Long = runCatching {
        JSONObject(file.readText()).optJSONObject(seam)?.optLong("failures") ?: 0L
    }.getOrDefault(0L)

    private class WriteFaultStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile var fail = true
        override val data: Flow<Preferences> = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (fail) throw IOException("synthetic private write failure")
            return delegate.updateData(transform)
        }
    }
}
