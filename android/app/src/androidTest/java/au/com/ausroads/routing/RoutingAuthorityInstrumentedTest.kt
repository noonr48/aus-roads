package au.com.ausroads.routing

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.ausroads.collectInstalledPacks
import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.di.InstalledPackPaths
import au.com.ausroads.di.InstalledPackPaths.RoutingSelection
import au.com.ausroads.di.PlacesHealth
import au.com.ausroads.di.RoutingInitOutcome
import au.com.ausroads.di.RoutingInitializer
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.routing.engine.RouteRequest
import au.com.ausroads.routing.engine.valhalla.ValhallaRoutingEngine
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android adoption/configuration/health crossing in isolated contexts, never owner packs.
 * Small nonempty files prove configuration/invalidation only, NOT graph readiness. The separately
 * named valid-tar case computes a real JNI route and fails if its staged prerequisite is absent.
 */
@RunWith(AndroidJUnit4::class)
class RoutingAuthorityInstrumentedTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var engine: ValhallaRoutingEngine
    private lateinit var health: PlacesHealth
    private lateinit var initializer: RoutingInitializer
    private lateinit var healthWriter: ExecutorService

    @Before
    fun emulatorGuardBeforeAnyFixture() {
        check(Build.MODEL.startsWith("sdk_gphone") && Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "component=routing-proof seam=guest-guard class=physical-device-refused"
        }
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(app.cacheDir.toPath(), "routing-authority-").toFile()
        val files = File(root, "files").apply { check(mkdirs()) }
        val noBackup = File(root, "no-backup").apply { check(mkdirs()) }
        val cache = File(root, "cache").apply { check(mkdirs()) }
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getNoBackupFilesDir(): File = noBackup
            override fun getCacheDir(): File = cache
        }
        engine = ValhallaRoutingEngine(context)
        health = PlacesHealth(context)
        healthWriter = PlacesHealth::class.java.getDeclaredField("writer")
            .apply { isAccessible = true }.get(health) as ExecutorService
        initializer = RoutingInitializer(context, engine, health)
    }

    @After
    fun removeOnlyIsolatedFixtures() {
        if (::healthWriter.isInitialized) {
            healthWriter.shutdown()
            check(healthWriter.awaitTermination(5, TimeUnit.SECONDS))
        }
        if (::root.isInitialized) check(root.deleteRecursively())
    }

    @Test
    fun adoptedCustomTarBeatsConventionalOrphanAndPrevious_andMapSearchDefaultsRemain(): Unit = runBlocking {
        val selected = fixtureTar()
        decoys()
        val tiles = component("v$VERSION/tiles.mbtiles")
        val search = component("v$VERSION/search.db")
        adopt(RELATIVE_TAR)
        assertThat(InstalledPackPaths.routing(context))
            .isEqualTo(RoutingSelection.Available(selected.canonicalFile))
        assertThat(InstalledPackPaths.component(context, "tilesPath", "tiles.mbtiles"))
            .isEqualTo(tiles.canonicalFile)
        assertThat(InstalledPackPaths.component(context, "searchPath", "search.db"))
            .isEqualTo(search.canonicalFile)
        initialize()
        assertThat(engine.isReady()).isTrue()
        assertThat(configuredTar()).isEqualTo(selected.canonicalPath)
        assertOutcome(RoutingInitOutcome.CONFIGURED, 0)
    }

    @Test
    fun missingNullOrAbsentRoutingInvalidatesReadyWithoutFallbackOrFailure(): Unit = runBlocking {
        fixtureTar()
        decoys()
        val absentStates = listOf(
            JSONObject().put("version", VERSION).toString(),
            JSONObject().put("version", VERSION).put("routingPath", JSONObject.NULL).toString(),
            null,
        )
        absentStates.forEach { metadata ->
            adopt(RELATIVE_TAR)
            initialize()
            assertThat(engine.isReady()).isTrue()
            if (metadata == null) check(stateFile().delete()) else stateFile().writeText(metadata)
            assertThat(InstalledPackPaths.routing(context)).isEqualTo(RoutingSelection.Unavailable)
            initialize()
            assertThat(engine.isReady()).isFalse()
            assertUnavailableFromMain()
            assertOutcome(RoutingInitOutcome.UNAVAILABLE, 0)
        }
    }

    @Test
    fun rejectedMetadataAndDeclaredComponentsInvalidateAndPersistFixedRecoveryWitness(): Unit = runBlocking {
        val selected = fixtureTar()
        val empty = component("v$VERSION/custom/empty.tar").apply { writeText("") }
        val directory = File(empty.parentFile, "directory.tar").apply { check(mkdir()) }
        decoys()
        adopt(RELATIVE_TAR)
        initialize()
        assertThat(engine.isReady()).isTrue()
        val invalid = listOf(
            "private invalid metadata", "", "x".repeat(16_385), "[]", "null",
            JSONObject().put("version", VERSION).put("routingPath", RELATIVE_TAR).toString() + "private trailing data",
            JSONObject().put("routingPath", RELATIVE_TAR).toString(),
            JSONObject().put("version", 2).put("routingPath", RELATIVE_TAR).toString(),
            JSONObject().put("version", "../private-proof").put("routingPath", RELATIVE_TAR).toString(),
        ) + listOf<Any>(42, true, JSONObject(), "", "  ", "../outside.tar", selected.path,
            "custom\\private-route.tar", "custom/private\u0000.tar", "custom/missing.tar",
            "custom/${empty.name}", "custom/${directory.name}").map { declared ->
            JSONObject().put("version", VERSION).put("routingPath", declared).toString()
        }
        invalid.forEachIndexed { index, metadata ->
            stateFile().writeText(metadata)
            assertThat(InstalledPackPaths.routing(context)).isEqualTo(RoutingSelection.Rejected)
            initialize()
            assertThat(engine.isReady()).isFalse()
            assertOutcome(RoutingInitOutcome.REJECTED, index + 1)
        }
        // A directory at the metadata path is corruption, not an absent optional component.
        check(stateFile().delete())
        check(stateFile().mkdir())
        initialize()
        assertOutcome(RoutingInitOutcome.REJECTED, invalid.size + 1)
        check(stateFile().delete())
        adopt(RELATIVE_TAR)
        initialize()
        assertThat(engine.isReady()).isTrue()
        assertThat(configuredTar()).isEqualTo(selected.canonicalPath)
        assertOutcome(RoutingInitOutcome.CONFIGURED, invalid.size + 1)
        assertThat(File(context.noBackupFilesDir, "places-health.json").length()).isAtMost(16_384L)
        assertThat(selected.readText()).isEqualTo(CONFIGURATION_ONLY_BYTES)
    }

    @Test
    fun metadataAndContainedOrEscapingSymlinksAreRejectedOnAndroid(): Unit = runBlocking {
        val selected = fixtureTar()
        adopt(RELATIVE_TAR)
        initialize()
        val metadata = File(root, "private-metadata.json").apply { writeText(stateFile().readText()) }
        check(stateFile().delete())
        Files.createSymbolicLink(stateFile().toPath(), metadata.toPath())
        initialize()
        assertThat(engine.isReady()).isFalse()
        assertOutcome(RoutingInitOutcome.REJECTED, 1)
        check(stateFile().delete())
        val inside = File(selected.parentFile, "alias.tar")
        Files.createSymbolicLink(inside.toPath(), selected.toPath())
        adopt("custom/alias.tar")
        initialize()
        assertOutcome(RoutingInitOutcome.REJECTED, 2)
        val outside = File(root, "private-outside.tar").apply { writeText(CONFIGURATION_ONLY_BYTES) }
        val escape = File(selected.parentFile, "escape.tar")
        Files.createSymbolicLink(escape.toPath(), outside.toPath())
        adopt("custom/escape.tar")
        initialize()
        assertOutcome(RoutingInitOutcome.REJECTED, 3)
        Files.createSymbolicLink(File(base(), "vlinked").toPath(), File(base(), "v$VERSION").toPath())
        stateFile().writeText(JSONObject().put("version", "linked").put("routingPath", RELATIVE_TAR).toString())
        initialize()
        assertOutcome(RoutingInitOutcome.REJECTED, 4)
        val moved = File(root, "relocated-packs")
        check(base().renameTo(moved))
        Files.createSymbolicLink(base().toPath(), moved.toPath())
        initialize()
        assertThat(engine.isReady()).isFalse()
        assertOutcome(RoutingInitOutcome.REJECTED, 5)
        assertUnavailableFromMain()
    }

    @Test
    fun actualConfigWriteFailureClearsReadinessAndSamePathRepairCanRetry(): Unit = runBlocking {
        val selected = fixtureTar()
        adopt(RELATIVE_TAR)
        initialize()
        assertThat(engine.isReady()).isTrue()
        val config = File(context.filesDir, "valhalla.json")
        check(config.delete())
        check(config.mkdir())
        val sentinel = File(config, "private-blocker").apply { writeText("preserve") }
        initialize() // same tar path: must really try the write, not return cached readiness
        assertThat(engine.isReady()).isFalse()
        assertOutcome(RoutingInitOutcome.CONFIGURATION_FAILED, 1)
        assertUnavailableFromMain()
        assertThat(sentinel.readText()).isEqualTo("preserve")
        check(config.deleteRecursively())
        initialize()
        assertThat(engine.isReady()).isTrue()
        assertThat(configuredTar()).isEqualTo(selected.canonicalPath)
        assertOutcome(RoutingInitOutcome.CONFIGURED, 1)
        config.writeText("private corrupted derived config")
        initialize() // a ready, unchanged path must also revalidate configuration
        assertThat(configuredTar()).isEqualTo(selected.canonicalPath)
        assertOutcome(RoutingInitOutcome.CONFIGURED, 1)
        assertThat(selected.readText()).isEqualTo(CONFIGURATION_ONLY_BYTES)
    }

    @Test
    fun existingBootFullRecordCollectorObservesSameVersionOptionalReplacementAndRepair(): Unit = runBlocking {
        fixtureTar()
        adopt(RELATIVE_TAR)
        val installed = MutableStateFlow<InstalledPack?>(record(null))
        installed.value = record(RELATIVE_TAR) // already replaced before registration
        val crossings = Channel<Int>(Channel.UNLIMITED)
        val calls = AtomicInteger()
        val collector = launch(Dispatchers.IO) {
            collectInstalledPacks({ installed }) {
                initializer.initialize()
                crossings.trySend(calls.incrementAndGet()).getOrThrow()
            }
        }
        try {
            assertThat(withTimeout(5_000) { crossings.receive() }).isEqualTo(1)
            assertThat(engine.isReady()).isTrue()
            assertOutcome(RoutingInitOutcome.CONFIGURED, 0)
            adopt(null)
            installed.value = record(null)
            assertThat(withTimeout(5_000) { crossings.receive() }).isEqualTo(2)
            assertThat(engine.isReady()).isFalse()
            assertOutcome(RoutingInitOutcome.UNAVAILABLE, 0)
            adopt(RELATIVE_TAR)
            installed.value = record(RELATIVE_TAR)
            assertThat(withTimeout(5_000) { crossings.receive() }).isEqualTo(3)
            assertThat(engine.isReady()).isTrue()
            assertOutcome(RoutingInitOutcome.CONFIGURED, 0)
        } finally {
            collector.cancelAndJoin()
            crossings.close()
        }
    }

    @Test
    fun adoptedValidTarComputesAdelaideRouteFromMain(): Unit = runBlocking {
        val staged = File("/data/local/tmp/valhalla_tiles.tar")
        check(staged.isFile && staged.length() > 0) {
            "component=routing-proof seam=valid-tiles class=prerequisite-missing"
        }
        val selected = File(base(), "v$VERSION/$RELATIVE_TAR").apply { check(parentFile!!.mkdirs()) }
        staged.copyTo(selected)
        adopt(RELATIVE_TAR)
        initialize()
        assertThat(engine.isReady()).isTrue()
        assertThat(configuredTar()).isEqualTo(selected.canonicalPath)
        val result = withContext(Dispatchers.Main) { engine.computeRoute(request()) }
        // These fixed Adelaide endpoints are over 2 km apart; km must not publish as meters.
        assertThat(result.distanceMeters).isAtLeast(2_000)
        assertThat(result.geometry).isNotEmpty()
        assertThat(result.maneuvers).isNotEmpty()
        assertThat(result.maneuvers.any { it.lengthMeters > 0 }).isTrue()
        assertOutcome(RoutingInitOutcome.CONFIGURED, 0)
    }

    private suspend fun initialize() = withContext(Dispatchers.IO) { initializer.initialize() }

    private fun base() = File(context.filesDir, "mappacks/au-sa")

    private fun stateFile() = File(base(), "current.json")

    private fun fixtureTar() = component("v$VERSION/$RELATIVE_TAR")

    private fun component(relative: String) = File(base(), relative).apply {
        parentFile!!.mkdirs()
        writeText(CONFIGURATION_ONLY_BYTES)
    }

    private fun decoys() {
        component("routing/valhalla_tiles.tar")
        component("v999/routing/orphan.tar")
        File(base(), "previous.json").writeText(
            JSONObject().put("version", "999").put("routingPath", "routing/orphan.tar").toString()
        )
    }

    private fun adopt(path: String?) {
        stateFile().writeText(
            JSONObject().put("version", VERSION).put("routingPath", path ?: JSONObject.NULL).toString()
        )
    }

    private fun configuredTar() = JSONObject(File(context.filesDir, "valhalla.json").readText())
        .getJSONObject("mjolnir").getString("tile_extract")

    private fun assertOutcome(outcome: RoutingInitOutcome, failures: Int) {
        healthWriter.submit {}.get(5, TimeUnit.SECONDS)
        val text = File(context.noBackupFilesDir, "places-health.json").readText()
        val row = JSONObject(text).getJSONObject("ROUTING_INIT")
        assertThat(row.getString("last_outcome")).isEqualTo(outcome.name)
        assertThat(row.optLong("failures", 0L)).isEqualTo(failures.toLong())
        assertThat(row.has(if (outcome.failed) "last_failure_ms" else "last_success_ms")).isTrue()
        assertThat(text).doesNotContain(root.path)
        assertThat(text).doesNotContain(VERSION)
        assertThat(text).doesNotContain("private-")
        assertThat(text).doesNotContain("34.9285")
        assertThat(text).doesNotContain("orphan.tar")
    }

    private fun assertUnavailableFromMain() {
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { withContext(Dispatchers.Main) { engine.computeRoute(request()) } }
        }
        assertThat(failure.message).contains("not initialized")
    }

    private fun record(path: String?) = InstalledPack(
        version = VERSION, regionCode = "AU-SA", installedAt = Instant.fromEpochMilliseconds(0),
        totalSizeBytes = 1, tilesPath = "tiles.mbtiles", routingPath = path,
        manifestSha256 = "a".repeat(64),
    )

    private fun request() = RouteRequest(
        origin = GeoPoint(latitude = -34.9285, longitude = 138.6007),
        destination = GeoPoint(latitude = -34.9066, longitude = 138.5950),
    )

    private companion object {
        const val VERSION = "private-proof"
        const val RELATIVE_TAR = "custom/private-route.tar"
        const val CONFIGURATION_ONLY_BYTES = "configuration-only fixture, not a usable routing graph"
    }
}
