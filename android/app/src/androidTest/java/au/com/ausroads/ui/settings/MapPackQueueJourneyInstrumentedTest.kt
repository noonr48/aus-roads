package au.com.ausroads.ui.settings

import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkRequest
import androidx.work.impl.WorkManagerImpl
import au.com.ausroads.AusRoadsApp
import au.com.ausroads.BuildConfig
import au.com.ausroads.MainActivity
import au.com.ausroads.R
import au.com.ausroads.data.pins.PinDatabase
import au.com.ausroads.data.routes.RouteHistoryDatabase
import au.com.ausroads.data.settings.settingsDataStore
import au.com.ausroads.offline.download.MapPackDownloadWorker
import au.com.ausroads.offline.download.MapPackManager
import au.com.ausroads.offline.download.state.DOWNLOAD_CANCEL_FAILED
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.reflect.Field
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real MainActivity/Hilt/native WorkManager journey. The negative is an explicitly
 * injected synchronous Cancel rejection, NOT backend corruption or a failed worker.
 * No application data fixtures, fabricated producer flows, or WorkInfo mutations.
 */
@RunWith(AndroidJUnit4::class)
class MapPackQueueJourneyInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var context: Context
    private lateinit var manager: MapPackManager
    private lateinit var native: WorkManager
    private lateinit var workLock: Any
    private lateinit var stateMutex: Mutex

    @Test
    fun withNetworkQueuedKeepRejectedCancelAndRetryPreserveAuthority(): Unit = runBlocking {
        // Fail, never skip or return an offline success, BEFORE any test work/screenshot.
        check(Build.MODEL.startsWith("sdk_gphone") && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Map-pack queue journey is emulator-only; never run on the physical phone"
        }
        check(BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == "debug" &&
            BuildConfig.FLAVOR == "withNetwork" && BuildConfig.CAN_DOWNLOAD_PACKS) {
            "Map-pack queue journey requires withNetworkDebug"
        }
        assertCleanupPrecedence()
        context = ApplicationProvider.getApplicationContext()
        val app = context.applicationContext as AusRoadsApp
        manager = app.mapPackManager.get()
        val backendField = field("workManager")
        native = backendField.get(manager) as WorkManager
        assertThat(native).isSameInstanceAs(WorkManager.getInstance(context))
        check(native is WorkManagerImpl) { "Expected actual native WorkManagerImpl" }
        workLock = checkNotNull(field("workLock").get(manager))
        stateMutex = field("stateMutex").get(manager) as Mutex
        check(workValue("recoverUnownedWork") == true) { "Existing manager Cancel fence; no state reset permitted" }
        val history = uniqueWork()
        check(history.all { it.state.isFinished }) { "Existing unfinished pack-download work; refusing to cancel it" }
        val store = field("packStateStore").get(manager) as PackStateStore
        val current = store.readCurrent()
        compose.waitUntil(10_000) { manager.installed.value == current }
        val before = snapshot(store)
        val oldCount = cancelRow()?.getLong("failureCount") ?: 0L
        check(oldCount < Long.MAX_VALUE) { "Cancel health counter exhausted" }

        // Real production worker/inputs/policy. Delay protects the retained native request;
        // the denied placeholder host is also outside the production endpoint allowlist.
        val retained = OneTimeWorkRequestBuilder<MapPackDownloadWorker>()
            .setInputData(Data.Builder().putString(MapPackDownloadWorker.KEY_PACK_URL, PLACEHOLDER_URL)
                .putString(MapPackDownloadWorker.KEY_PACK_VERSION, PLACEHOLDER_VERSION).build())
            .setInitialDelay(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(UNIQUE_WORK).build()
        val created = linkedSetOf(retained.id)
        var scenario: ActivityScenario<MainActivity>? = null
        var historyRetired = false
        var bodyFailure: Throwable? = null
        try {
            // Fence the known production writer while checking and submitting. This
            // disposable journey is the sole input actor; unknown concurrent work is
            // not a fixture we may cancel. KEEP retires finished rows in 2.9.1.
            synchronized(workLock) {
                assertThat(uniqueWork()).containsExactlyElementsIn(history)
                native.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, retained).result.get(10, TimeUnit.SECONDS)
                historyRetired = true
            }
            nativeBarrier()
            awaitQueuedOwner(retained.id)
            assertNativeOwner(retained.id, WorkInfo.State.ENQUEUED, history)
            val activity = ActivityScenario.launch(MainActivity::class.java)
            scenario = activity
            lateinit var settings: SettingsViewModel
            activity.onActivity {
                assertThat(it.application).isSameInstanceAs(app)
                settings = ViewModelProvider(it)[SettingsViewModel::class.java]
            }
            compose.waitUntil(10_000) { settings.storeState.value.canEdit && !settings.storeState.value.isSaving }
            compose.onAllNodesWithText(context.getString(R.string.nav_settings)).onLast().performClick()
            assertQueuedUi(rejected = false)

            val enqueueGeneration = workValue("workGeneration") as Long
            lateinit var discarded: UUID
            compose.runOnIdle {
                manager.startDownload(PLACEHOLDER_URL, PLACEHOLDER_VERSION)
                discarded = checkNotNull(workValue("requestedWorkId") as? UUID)
            }
            check(discarded != retained.id && history.none { it.id == discarded }) { "Uncertain new request identity" }
            created += discarded
            // startDownload returns void. Fence its actual EnqueueRunnable, not an assumed
            // delay or unchanged UI, before proving native KEEP discarded the new ID.
            nativeBarrier()
            assertThat(native.getWorkInfoById(discarded).get(10, TimeUnit.SECONDS)).isNull()
            assertThat(workValue("workGeneration")).isEqualTo(enqueueGeneration + 1)
            awaitQueuedOwner(retained.id)
            assertThat(workValue("requestedWorkId")).isEqualTo(discarded)
            assertThat(manager.downloadError.value).isNull()
            assertNativeOwner(retained.id, WorkInfo.State.ENQUEUED, history)
            assertQueuedUi(rejected = false)
            screenshot("map-pack-queue-keep")

            val rejectedGeneration = (workValue("workGeneration") as Long) + 1
            val rejectedAt = System.currentTimeMillis()
            val rejection = RejectCancelWorkManager(native)
            var injectionFailure: Throwable? = null
            try {
                synchronized(workLock) {
                    check(backendField.get(manager) === native)
                    backendField.set(manager, rejection)
                    check(backendField.get(manager) === rejection) { "Cancel injection did not bind" }
                }
                compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
                compose.waitUntil(10_000) { cancelRow()?.getLong("failureCount") == oldCount + 1 }
                val row = checkNotNull(cancelRow())
                assertThat(rejection.rejectedNames).containsExactly(UNIQUE_WORK)
                assertThat(row.getLong("generation")).isEqualTo(rejectedGeneration)
                assertThat(row.getLong("lastFailureAtMillis")).isAtLeast(rejectedAt)
                assertThat(row.getLong("lastFailureAtMillis")).isAtMost(System.currentTimeMillis())
                assertThat(workValue("workGeneration")).isEqualTo(rejectedGeneration)
                assertThat(workValue("pendingCancel")).isNull()
                assertThat(workValue("requestedWorkId")).isEqualTo(discarded)
                awaitQueuedOwner(retained.id)
                assertThat(manager.downloadError.value).isEqualTo(DOWNLOAD_CANCEL_FAILED)
                assertNativeOwner(retained.id, WorkInfo.State.ENQUEUED, history)
                assertQueuedUi(rejected = true)
                screenshot("map-pack-queue-cancel-rejected")
                assertPreserved(before, store)
            } catch (failure: Throwable) {
                injectionFailure = failure
                throw failure
            } finally {
                val failures = mutableListOf<Throwable>()
                try { restoreBackend(backendField) } catch (failure: Throwable) { failures += failure }
                finishCleanup(injectionFailure, failures)
            }

            // Retry the real UI Cancel, NOT the worker's download Retry button.
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            nativeBarrier()
            compose.waitUntil(10_000) {
                native.getWorkInfoById(retained.id).get(10, TimeUnit.SECONDS)?.state == WorkInfo.State.CANCELLED &&
                    manager.inFlight.value == null && manager.downloadError.value == null && workValue("pendingCancel") == null
            }
            assertNativeOwner(retained.id, WorkInfo.State.CANCELLED, history)
            assertThat(workValue("workGeneration")).isEqualTo(rejectedGeneration + 1)
            assertThat(backendField.get(manager)).isSameInstanceAs(native)
            assertIdleUi(before.current)
            assertThat(checkNotNull(cancelRow()).getLong("failureCount")).isEqualTo(oldCount + 1)
            assertThat(checkNotNull(cancelRow()).getLong("generation")).isEqualTo(rejectedGeneration)
            assertPreserved(before, store)
            screenshot("map-pack-queue-cancelled")
        } catch (failure: Throwable) {
            bodyFailure = failure
            throw failure
        } finally {
            // Attempt every cleanup, retaining the body failure as primary. Only
            // TEST-created UUIDs may be cancelled; retain the current native/health
            // records. Public KEEP has already retired the prior terminal WorkSpecs.
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (failure: Throwable) { failures += failure } }
            cleanup { restoreBackend(backendField) }
            created.forEach { id -> cleanup {
                native.cancelWorkById(id).result.get(10, TimeUnit.SECONDS)
                check(native.getWorkInfoById(id).get(10, TimeUnit.SECONDS)?.state?.isFinished != false)
            } }
            cleanup { scenario?.close() }
            try { assertPreserved(before, store) } catch (failure: Throwable) { failures += failure }
            cleanup {
                val remaining = uniqueWork().filterNot { it.id in created }
                if (historyRetired) {
                    assertThat(remaining).isEmpty()
                    assertRetiredHistory(history)
                } else {
                    assertThat(remaining).containsExactlyElementsIn(history)
                }
            }
            finishCleanup(bodyFailure, failures)
        }
    }

    /** Cleanup is fatal on its own, never a replacement for the original failure. */
    private fun finishCleanup(primary: Throwable?, failures: List<Throwable>) {
        val first = primary ?: failures.firstOrNull() ?: return
        failures.filter { it !== first }.forEach(first::addSuppressed)
        if (primary == null) throw first
    }

    /** Caught failure controls exercise the same helper before any work/data fixture. */
    private fun assertCleanupPrecedence() {
        fun simulate(body: Throwable?, failures: List<Throwable>) {
            var primary: Throwable? = null
            try { if (body != null) throw body }
            catch (failure: Throwable) { primary = failure; throw failure }
            finally { finishCleanup(primary, failures) }
        }
        val body = IllegalStateException("Synthetic body failure")
        val cleanup = IllegalStateException("Synthetic cleanup failure")
        check(runCatching { simulate(body, listOf(cleanup, body)) }.exceptionOrNull() === body)
        check(body.suppressed.single() === cleanup)
        val cleanupOnly = IllegalStateException("Synthetic cleanup-only failure")
        val additional = IllegalStateException("Synthetic additional cleanup failure")
        check(runCatching { simulate(null, listOf(cleanupOnly, additional)) }.exceptionOrNull() === cleanupOnly)
        check(cleanupOnly.suppressed.single() === additional)
        check(runCatching { simulate(null, emptyList()) }.isSuccess)
    }

    private fun field(name: String): Field = MapPackManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun workValue(name: String): Any? = synchronized(workLock) { field(name).get(manager) }
    private fun restoreBackend(field: Field) = synchronized(workLock) {
        field.set(manager, native)
        check(field.get(manager) === native) { "Original WorkManager not restored" }
    }
    private fun nativeBarrier() {
        val done = CountDownLatch(1)
        (native as WorkManagerImpl).workTaskExecutor.serialTaskExecutor.execute { done.countDown() }
        check(done.await(10, TimeUnit.SECONDS)) { "Native serial operation barrier timed out" }
    }
    private fun uniqueWork(): List<WorkInfo> = native.getWorkInfosForUniqueWork(UNIQUE_WORK).get(10, TimeUnit.SECONDS)
    private fun awaitQueuedOwner(id: UUID) = compose.waitUntil(10_000) {
        workValue("ownedWorkId") == id && manager.inFlight.value == DownloadProgress(DownloadProgress.Phase.QUEUED, 0)
    }
    private fun assertNativeOwner(id: UUID, state: WorkInfo.State, history: List<WorkInfo>) {
        val infos = uniqueWork()
        assertThat(infos.map { it.id }).containsExactly(id)
        assertRetiredHistory(history)
        val owner = infos.single { it.id == id }
        assertThat(owner.state).isEqualTo(state)
        assertThat(native.getWorkInfoById(id).get(10, TimeUnit.SECONDS)).isEqualTo(owner)
        assertThat(owner.tags).contains(UNIQUE_WORK)
        assertThat(owner.runAttemptCount).isEqualTo(0)
        assertThat(owner.progress).isEqualTo(Data.EMPTY)
        assertThat(owner.outputData).isEqualTo(Data.EMPTY)
        if (!state.isFinished) {
            assertThat(workValue("ownedWorkId")).isEqualTo(id)
            val observed = workValue("ownedWorkInfo") as WorkInfo
            assertThat(observed.id).isEqualTo(id)
            assertThat(observed.state).isEqualTo(state)
        }
    }
    /** Pinned KEEP replaces only finished predecessor rows; saved app state is separate. */
    private fun assertRetiredHistory(history: List<WorkInfo>) {
        history.forEach { prior ->
            assertThat(prior.state.isFinished).isTrue()
            assertThat(native.getWorkInfoById(prior.id).get(10, TimeUnit.SECONDS)).isNull()
        }
    }
    private fun assertQueuedUi(rejected: Boolean) {
        compose.onNodeWithText(context.getString(R.string.download_queued)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.cancel)).assertCountEquals(1)
        compose.onNodeWithText(context.getString(R.string.cancel)).performScrollTo().assertIsDisplayed().assertIsEnabled()
        if (rejected) compose.onNodeWithText(context.getString(R.string.map_pack_cancel_failed)).performScrollTo().assertIsDisplayed()
        else compose.onNodeWithText(context.getString(R.string.map_pack_cancel_failed)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.download_queued)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed().assertIsEnabled()
        absent(R.string.map_pack_retry, R.string.map_pack_worker_error, R.string.map_pack_action_error,
            R.string.download_map_pack, R.string.settings_redownload, R.string.settings_map_pack_installed,
            R.string.settings_map_pack_uninstall, R.string.map_pack_uninstalled, R.string.download_already_up_to_date,
            R.string.download_checking, R.string.download_verifying, R.string.download_extracting, R.string.download_installing)
        compose.onNodeWithText(SYNTHETIC_FAILURE).assertDoesNotExist()
        compose.onNodeWithText(DOWNLOAD_CANCEL_FAILED).assertDoesNotExist()
    }
    private fun assertIdleUi(current: InstalledPack?) {
        absent(R.string.download_queued, R.string.cancel, R.string.map_pack_cancel_failed,
            R.string.map_pack_retry, R.string.map_pack_worker_error, R.string.map_pack_action_error,
            R.string.map_pack_uninstalled, R.string.download_already_up_to_date)
        val label = if (current != null && current.version.isNotEmpty()) R.string.settings_redownload else R.string.download_map_pack
        compose.onNodeWithText(context.getString(label)).performScrollTo().assertIsDisplayed().assertIsEnabled()
    }
    private fun absent(vararg resources: Int) = resources.forEach {
        compose.onAllNodesWithText(context.getString(it)).assertCountEquals(0)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { File(context.cacheDir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    /** Read only the production atomic file; malformed prior/native evidence fails visibly. */
    private fun cancelRow(): JSONObject? {
        val file = File(context.noBackupFilesDir, "pack-download-CANCEL.failed")
        if (!file.exists()) return null
        check(file.isFile && file.length() in 1L..1024L) { "Unreadable initial/native Cancel health row" }
        val raw = file.readText()
        val row = JSONObject(raw)
        assertThat(row.keys().asSequence().toSet()).containsExactly("schema", "component", "seam", "class",
            "failureCount", "lastFailureAtMillis", "generation")
        listOf("schema", "failureCount", "lastFailureAtMillis", "generation").forEach {
            check(row.get(it) is Int || row.get(it) is Long) { "Non-integer native health value" }
        }
        assertThat(row.getInt("schema")).isEqualTo(1)
        assertThat(row.get("component")).isEqualTo("map-pack")
        assertThat(row.get("seam")).isEqualTo("CANCEL")
        assertThat(row.get("class")).isEqualTo("OPERATION_REJECTED")
        check(row.getLong("failureCount") > 0 && row.getLong("lastFailureAtMillis") > 0 && row.getLong("generation") >= 0)
        listOf(SYNTHETIC_FAILURE, PLACEHOLDER_URL, PLACEHOLDER_VERSION, "IllegalStateException", "stack", "output").forEach {
            assertThat(raw).doesNotContain(it)
        }
        return row
    }

    private data class Snapshot(val current: InstalledPack?, val previous: InstalledPack?, val adopted: InstalledPack?,
        val packBytes: Map<String, String>, val settings: Preferences, val preferenceBytes: Map<String, String>,
        val pins: DatabaseSnapshot?, val routes: DatabaseSnapshot?)
    private data class DatabaseSnapshot(val version: Int, val columns: List<String>, val rows: List<List<Any?>>,
        val schema: List<List<Any?>>)
    private suspend fun snapshot(store: PackStateStore): Snapshot = stateMutex.withLock {
        val base = checkNotNull(store.packDir("").parentFile)
        val current = store.readCurrent()
        val previous = store.readPrevious()
        check((current != null || !File(base, "current.json").exists()) &&
            (previous != null || !File(base, "previous.json").exists())) { "Undecodable existing pack authority" }
        assertThat(manager.installed.value).isEqualTo(current)
        val preferences = withTimeout(10_000) { context.settingsDataStore.data.first() }
        Snapshot(current, previous, manager.installed.value, treeBytes(base), preferences,
            treeBytes(File(context.filesDir, "datastore")), database(context.getDatabasePath(PinDatabase.DATABASE_NAME), "pins"),
            database(context.getDatabasePath(RouteHistoryDatabase.DATABASE_NAME), "route_history"))
    }
    private suspend fun assertPreserved(before: Snapshot, store: PackStateStore) {
        val after = snapshot(store)
        assertThat(after.current).isEqualTo(before.current)
        assertThat(after.previous).isEqualTo(before.previous)
        assertThat(after.adopted).isEqualTo(before.adopted)
        assertThat(after.packBytes).isEqualTo(before.packBytes)
        assertThat(after.settings).isEqualTo(before.settings)
        assertThat(after.preferenceBytes).isEqualTo(before.preferenceBytes)
        // MainActivity may create an absent empty Room DB; existing schemas/all raw
        // columns/rows (including future blobs) must remain exactly unchanged.
        if (before.pins != null) assertThat(after.pins).isEqualTo(before.pins) else assertThat(after.pins?.rows.orEmpty()).isEmpty()
        if (before.routes != null) assertThat(after.routes).isEqualTo(before.routes) else assertThat(after.routes?.rows.orEmpty()).isEmpty()
    }
    private fun treeBytes(root: File): Map<String, String> {
        if (!root.exists()) return mapOf("." to "absent")
        val result = sortedMapOf<String, String>()
        fun visit(file: File) {
            check(!Files.isSymbolicLink(file.toPath())) { "Uncertain preservation path: symbolic link" }
            val key = if (file == root) "." else file.relativeTo(root).path
            if (file.isDirectory) {
                result[key] = "directory"
                checkNotNull(file.listFiles()) { "Unreadable preservation directory" }.forEach(::visit)
            } else {
                check(file.isFile && file.canRead()) { "Unreadable preservation bytes" }
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                }
                result[key] = "${file.length()}:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
        }
        visit(root)
        return result
    }
    private fun database(file: File, table: String): DatabaseSnapshot? {
        if (!file.exists()) return null
        check(file.isFile && file.canRead()) { "Unreadable native database" }
        // A corrupt app database must fail the read, never invoke Android's deleting error handler.
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY,
            DatabaseErrorHandler { error("Read-only native database corruption; refuse reset") }).use { db ->
            db.rawQuery("SELECT * FROM $table ORDER BY id", null).use { cursor ->
                DatabaseSnapshot(db.version, cursor.columnNames.toList(), rows(cursor), db.rawQuery(
                    "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name", null).use(::rows))
            }
        }
    }
    private fun rows(cursor: Cursor): List<List<Any?>> = buildList {
        while (cursor.moveToNext()) add(List(cursor.columnCount) { column -> when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
            else -> error("Unknown native database value type")
        } })
    }

    /** WorkManager 2.9.1 ABI: every other entry point delegates unchanged to the real singleton. */
    private class RejectCancelWorkManager(private val delegate: WorkManager) : WorkManager() {
        val rejectedNames = CopyOnWriteArrayList<String>()
        override fun cancelUniqueWork(name: String): Operation {
            rejectedNames += name
            throw IllegalStateException(SYNTHETIC_FAILURE)
        }
        override fun getConfiguration() = delegate.configuration
        override fun enqueue(requests: List<WorkRequest>) = delegate.enqueue(requests)
        override fun beginWith(requests: List<OneTimeWorkRequest>) = delegate.beginWith(requests)
        override fun beginUniqueWork(name: String, policy: ExistingWorkPolicy, requests: List<OneTimeWorkRequest>) = delegate.beginUniqueWork(name, policy, requests)
        override fun enqueueUniqueWork(name: String, policy: ExistingWorkPolicy, requests: List<OneTimeWorkRequest>) = delegate.enqueueUniqueWork(name, policy, requests)
        override fun enqueueUniquePeriodicWork(name: String, policy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest) = delegate.enqueueUniquePeriodicWork(name, policy, request)
        override fun cancelWorkById(id: UUID) = delegate.cancelWorkById(id)
        override fun cancelAllWorkByTag(tag: String) = delegate.cancelAllWorkByTag(tag)
        override fun cancelAllWork() = delegate.cancelAllWork()
        override fun createCancelPendingIntent(id: UUID) = delegate.createCancelPendingIntent(id)
        override fun pruneWork() = delegate.pruneWork()
        override fun getLastCancelAllTimeMillisLiveData() = delegate.lastCancelAllTimeMillisLiveData
        override fun getLastCancelAllTimeMillis() = delegate.lastCancelAllTimeMillis
        override fun getWorkInfoByIdLiveData(id: UUID) = delegate.getWorkInfoByIdLiveData(id)
        override fun getWorkInfoByIdFlow(id: UUID) = delegate.getWorkInfoByIdFlow(id)
        override fun getWorkInfoById(id: UUID) = delegate.getWorkInfoById(id)
        override fun getWorkInfosByTagLiveData(tag: String) = delegate.getWorkInfosByTagLiveData(tag)
        override fun getWorkInfosByTagFlow(tag: String) = delegate.getWorkInfosByTagFlow(tag)
        override fun getWorkInfosByTag(tag: String) = delegate.getWorkInfosByTag(tag)
        override fun getWorkInfosForUniqueWorkLiveData(name: String) = delegate.getWorkInfosForUniqueWorkLiveData(name)
        override fun getWorkInfosForUniqueWorkFlow(name: String) = delegate.getWorkInfosForUniqueWorkFlow(name)
        override fun getWorkInfosForUniqueWork(name: String) = delegate.getWorkInfosForUniqueWork(name)
        override fun getWorkInfosLiveData(query: WorkQuery) = delegate.getWorkInfosLiveData(query)
        override fun getWorkInfosFlow(query: WorkQuery) = delegate.getWorkInfosFlow(query)
        override fun getWorkInfos(query: WorkQuery) = delegate.getWorkInfos(query)
        override fun updateWork(request: WorkRequest) = delegate.updateWork(request)
    }
    private companion object {
        const val UNIQUE_WORK = "pack-download"
        const val PLACEHOLDER_URL = "https://example.invalid/native-queue-must-not-download.zip"
        const val PLACEHOLDER_VERSION = "native-queue-placeholder"
        const val SYNTHETIC_FAILURE = "Synthetic Cancel operation rejection"
    }
}
