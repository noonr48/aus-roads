package au.com.ausroads.offline.download

import android.content.Context
import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.offline.download.download.PackDownloader
import au.com.ausroads.offline.download.download.PackExtractor
import au.com.ausroads.offline.download.download.PackInstaller
import au.com.ausroads.offline.download.download.PackVerifier
import au.com.ausroads.offline.download.eviction.EvictionManager
import au.com.ausroads.offline.download.state.DownloadProgress
import au.com.ausroads.offline.download.state.InstalledPack
import au.com.ausroads.offline.download.state.PackStateStore
import au.com.ausroads.offline.pack.OsmSource
import au.com.ausroads.offline.pack.PackComponents
import au.com.ausroads.offline.pack.PackManifest
import au.com.ausroads.offline.pack.RoutingComponent
import au.com.ausroads.offline.pack.SearchComponent
import au.com.ausroads.offline.pack.TileComponent
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Calls the exact installDownloadedPack tail used by doWork, with real ZIP IO,
 * PackExtractor, PackVerifier, PackInstaller, EvictionManager and temp PackStateStore.
 * Context.filesDir and the resume control's HTTP transport are mocked, never the pipeline.
 * Foreground/live networking/WorkManager scheduling and the UI Retry action remain
 * parent-owned runtime checks, not claimed here.
 */
class ReferencedPackRetryTest {
    private lateinit var tempDir: File
    private lateinit var store: PackStateStore
    private lateinit var eviction: EvictionManager
    private lateinit var installer: PackInstaller
    private val extractor = PackExtractor()
    private val components = linkedMapOf(
        "tiles.mbtiles" to "new tile bytes",
        "search.db" to "new search bytes",
        "routing/valhalla_tiles.tar" to "new routing bytes",
    )

    @Before
    fun setUp() {
        tempDir = createTempDir("referenced-pack-retry")
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir
        store = PackStateStore(context)
        eviction = EvictionManager(store)
        installer = PackInstaller(PackVerifier(), store, eviction)
    }

    @After
    fun tearDown() {
        // Never follow a regression fixture's redirect during teardown either.
        Files.walk(tempDir.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    @Test
    fun `worker tail refuses linked scratch without touching complete tree`() = runTest {
        seedReferences()
        val zip = zipInScratch()
        val scratch = zip.parentFile!!
        File(scratch, "resume.marker").writeBytes(byteArrayOf(0, 1, -1, 42))
        File(tempDir, "outside/user.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(-1, 0, 42, 1))
        }
        Files.createSymbolicLink(File(scratch, "nested-redirect").toPath(), store.packDir("current").toPath())
        val before = completeSnapshot()
        val phases = mutableListOf<DownloadProgress.Phase>()

        val outcome = finish(zip, "current", null) { phases.add(it) }

        // First assert preservation: the old raw recursive tail deletes adopted
        // components through nested-redirect, even on a normal referenced refusal.
        assertThat(completeSnapshot()).isEqualTo(before)
        assertFailure(outcome, "pack_scratch_invalid")
        assertThat(phases).isEmpty()
    }

    @Test
    fun `stale current Retry neither extracts over nor cleans referenced bytes`() = runTest {
        seedReferences()
        val before = snapshot()
        val phases = mutableListOf<DownloadProgress.Phase>()
        val zip = zipInScratch()
        val outcome = finish(zip, "current", manifestJson("current")) { phases.add(it) }
        assertFailure(outcome, "pack_target_referenced")
        assertThat(phases).isEmpty()
        assertThat(snapshot()).isEqualTo(before)
        assertThat(store.partialDir().exists()).isFalse()
    }

    @Test
    fun `stale previous Retry neither extracts over nor cleans referenced bytes`() = runTest {
        seedReferences()
        val before = snapshot()
        val outcome = finish(zipInScratch(), "previous", null)
        assertFailure(outcome, "pack_target_referenced")
        assertThat(snapshot()).isEqualTo(before)
        assertThat(store.partialDir().exists()).isFalse()
    }

    @Test
    fun `late failure cleanup rechecks both live references and removes only unreferenced target`() = runTest {
        // Simulate a catch-all arriving after current/previous references were published.
        seedReferences()
        val before = snapshot()
        val rejected = store.packDir("unreferenced").apply { mkdirs() }
        File(rejected, "half-written").writeText("discard")
        installer.cleanupFailedAttempt("current")
        installer.cleanupFailedAttempt("previous")
        installer.cleanupFailedAttempt("unreferenced")
        assertThat(rejected.exists()).isFalse()
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `supplied corrupt or null JSON fails before extraction with fixed persisted token`() = runTest {
        seedReferences()
        val before = snapshot()
        listOf("{}", "null", "{\"private-marker\":", "{\"packVersion\":[]}").forEach { corrupt ->
            val phases = mutableListOf<DownloadProgress.Phase>()
            val outcome = finish(zipInScratch(), "new", corrupt) { phases.add(it) }
            assertFailure(outcome, "pack_manifest_invalid")
            assertThat(phases).isEmpty()
            assertThat(store.packDir("new").exists()).isFalse()
            assertThat(store.partialDir().exists()).isFalse()
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    @Test
    fun `valid new version verifies all components and rotates current without damaging retained bytes`() = runTest {
        seedReferences()
        val currentBytes = snapshot(store.packDir("current"))
        val oldCurrent = store.readCurrent()
        val phases = mutableListOf<DownloadProgress.Phase>()
        val raw = manifestJson("new").dropLast(1) + ",\"futureField\":{\"enabled\":true}}"
        val outcome = finish(zipInScratch(), "new", raw) { phases.add(it) }
        assertThat(outcome).isEqualTo(PackInstaller.Outcome.Installed)
        assertThat(phases).containsExactly(
            DownloadProgress.Phase.EXTRACTING, DownloadProgress.Phase.VERIFYING,
            DownloadProgress.Phase.INSTALLING,
        ).inOrder()
        assertThat(store.readPrevious()).isEqualTo(oldCurrent)
        assertThat(snapshot(store.packDir("current"))).isEqualTo(currentBytes)
        assertThat(store.packDir("previous").exists()).isFalse() // no longer referenced after rotation
        assertThat(store.readCurrent()?.version).isEqualTo("new")
        assertThat(store.readCurrent()?.routingPath).isEqualTo("routing/valhalla_tiles.tar")
        assertThat(store.readCurrent()?.manifestSha256).isEqualTo(sha256(raw.toByteArray()))
        components.forEach { (path, bytes) ->
            assertThat(File(store.packDir("new"), path).readText()).isEqualTo(bytes)
        }
        assertThat(store.partialDir().exists()).isFalse()
    }

    @Test
    fun `absent manifest retains deliberately supported legacy install`() = runTest {
        val zip = zipInScratch()
        val zipLength = zip.length()
        assertThat(finish(zip, "legacy", null)).isEqualTo(PackInstaller.Outcome.Installed)
        val current = store.readCurrent()
        assertThat(current?.version).isEqualTo("legacy")
        assertThat(current?.totalSizeBytes).isEqualTo(zipLength)
        assertThat(current?.manifestSha256).isEmpty()
        assertThat(current?.routingPath).isNull()
        assertThat(File(store.packDir("legacy"), "tiles.mbtiles").readText()).isEqualTo(components["tiles.mbtiles"])
        assertThat(store.partialDir().exists()).isFalse()
    }

    @Test
    fun `real hash mismatch deletes poison and new target but preserves current previous`() = runTest {
        seedReferences()
        val before = snapshot()
        val badHash = manifestJson("new", routingHash = "not-the-real-hash")
        val outcome = finish(zipInScratch(), "new", badHash)
        assertThat(outcome).isInstanceOf(PackInstaller.Outcome.VerificationFailed::class.java)
        assertThat((outcome as PackInstaller.Outcome.VerificationFailed).message).contains("routing")
        assertThat(store.packDir("new").exists()).isFalse()
        assertThat(store.partialDir().exists()).isFalse()
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `unreadable adopted metadata cannot become permission to overwrite or clean target`() = runTest {
        seedReferences()
        val base = store.packDir("").parentFile!!
        File(base, "current.json").writeText("{private-corrupt-metadata")
        val target = store.packDir("new").apply { mkdirs() }
        File(target, "tiles.mbtiles").writeText("unknown-reference-sentinel")
        val before = snapshot()
        assertFailure(finish(zipInScratch(), "new", null), "pack_state_unreadable")
        assertThat(snapshot()).isEqualTo(before)
        assertThat(store.partialDir().exists()).isFalse()
    }

    @Test
    fun `reconciliation refuses unreadable references without deleting unknown installed bytes`() = runTest {
        listOf("current.json", "previous.json").forEach { metadata ->
            seedReferences()
            File(store.packDir("unknown"), "sentinel").apply {
                parentFile!!.mkdirs()
                writeText("unknown reference must survive")
            }
            File(store.packDir("").parentFile!!, metadata).writeText("{unreadable-reference")
            val before = snapshot()
            val failure = runCatching { eviction.reconcile() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(failure?.message).isEqualTo(
                "component=map-pack-state seam=reference-read class=metadata-unreadable",
            )
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    @Test
    fun `direct adoption refuses unreadable references before publication or stale cleanup`() = runTest {
        seedReferences()
        File(store.packDir("").parentFile!!, "previous.json").writeText("{unreadable-reference")
        val before = snapshot()
        val failure = runCatching { eviction.onNewInstall(installed("new")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).contains("class=metadata-unreadable")
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `new installation preserves readable previous when current record is absent`() = runTest {
        seedReferences()
        store.clearCurrent()
        val previous = store.readPrevious()
        val bytes = snapshot(store.packDir("previous"))
        val metadata = File(store.packDir("").parentFile!!, "previous.json").readBytes().toList()
        assertThat(finish(zipInScratch(), "new", manifestJson("new")))
            .isEqualTo(PackInstaller.Outcome.Installed)
        assertThat(store.readCurrent()?.version).isEqualTo("new")
        assertThat(store.readPrevious()).isEqualTo(previous)
        assertThat(snapshot(store.packDir("previous"))).isEqualTo(bytes)
        assertThat(File(store.packDir("").parentFile!!, "previous.json").readBytes().toList())
            .isEqualTo(metadata)
    }

    @Test
    fun `cancellation after actual extraction is control flow and scratch can be reused`() = runTest {
        seedReferences()
        val before = snapshot()
        val zip = zipInScratch()
        val zipBytes = zip.readBytes().toList()
        val cancel = CancellationException("bounded cancel control")
        var escaped: CancellationException? = null
        try {
            finish(zip, "new", manifestJson("new")) { phase ->
                if (phase == DownloadProgress.Phase.VERIFYING) throw cancel
            }
        } catch (caught: CancellationException) {
            escaped = caught
        }
        // Coroutine stacktrace recovery may copy an exception across the IO boundary.
        // Require cancellation control flow and the original cause, not JVM object identity.
        val cancellation = checkNotNull(escaped)
        assertThat(cancellation.message).isEqualTo(cancel.message)
        assertThat(generateSequence<Throwable>(cancellation) { it.cause }
            .take(8).any { it === cancel }).isTrue()
        assertThat(zip.readBytes().toList()).isEqualTo(zipBytes)
        assertThat(snapshot().filterKeys { !it.startsWith("vnew/") }).isEqualTo(before)
        assertThat(store.readCurrent()?.version).isEqualTo("current")
        assertThat(store.readPrevious()?.version).isEqualTo("previous")
        // This cancellation happened after download finished: the actual downloader
        // sends Range, handles a full-archive 416, then restarts once without Range.
        val requests = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val range = request.headers[HttpHeaders.Range]
                    requests.add(range)
                    if (range != null) {
                        respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable)
                    } else {
                        respond(zipBytes.toByteArray(), HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentLength to listOf(zipBytes.size.toString())))
                    }
                }
            }
        }
        try {
            PackDownloader(client).download("https://example.com/pack.zip", zip) { _, _ -> }
            assertThat(requests).containsExactlyElementsIn(listOf("bytes=${zipBytes.size}-", null)).inOrder()
            assertThat(zip.readBytes().toList()).isEqualTo(zipBytes)
            assertThat(finish(zip, "new", manifestJson("new"))).isEqualTo(PackInstaller.Outcome.Installed)
            assertThat(store.partialDir().exists()).isFalse()
        } finally {
            client.close()
        }
    }

    @Test
    fun `late Error cleans extracted target and scratch without deleting retained packs`() = runTest {
        seedReferences()
        val before = snapshot()
        val outcome = finish(zipInScratch(), "new", null) { phase ->
            if (phase == DownloadProgress.Phase.VERIFYING) throw Error("bounded late Error")
        }
        assertFailure(outcome, "bounded late Error")
        assertThat(store.packDir("new").exists()).isFalse()
        assertThat(store.partialDir().exists()).isFalse()
        assertThat(snapshot()).isEqualTo(before)
    }

    @Test(timeout = 15_000)
    fun `competing retained Retry waits for adoption then refuses without replacing winner bytes`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstZip = writeZip(File(tempDir, "first.zip"), components)
        val other = components.mapValues { (_, value) -> "$value overwritten" }
        val secondZip = writeZip(File(tempDir, "second.zip"), other)
        val first = async(Dispatchers.IO) {
            finish(firstZip, "new", manifestJson("new")) { phase ->
                if (phase == DownloadProgress.Phase.VERIFYING) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        try {
            // UNDISPATCHED + IO enters the actual shared-lock attempt before async returns.
            val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                finish(secondZip, "new", null)
            }
            assertThat(second.isCompleted).isFalse()
            release.complete(Unit)
            assertThat(first.await()).isEqualTo(PackInstaller.Outcome.Installed)
            assertFailure(second.await(), "pack_target_referenced")
            components.forEach { (path, bytes) ->
                assertThat(File(store.packDir("new"), path).readText()).isEqualTo(bytes)
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test(timeout = 15_000)
    fun `launch reconciliation cannot delete extraction in progress or use stale references`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val zip = zipInScratch()
        val install = async(Dispatchers.IO) {
            finish(zip, "new", manifestJson("new")) { phase ->
                if (phase == DownloadProgress.Phase.VERIFYING) {
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        try {
            val reconcile = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { eviction.reconcile() }
            assertThat(reconcile.isCompleted).isFalse()
            assertThat(File(store.packDir("new"), "tiles.mbtiles").readText()).isEqualTo(components["tiles.mbtiles"])
            release.complete(Unit)
            assertThat(install.await()).isEqualTo(PackInstaller.Outcome.Installed)
            reconcile.await()
            assertThat(store.readCurrent()?.version).isEqualTo("new")
            assertThat(File(store.packDir("new"), "routing/valhalla_tiles.tar").exists()).isTrue()
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `archive sibling-prefix escape cannot overwrite referenced component`() = runTest {
        seedReferences(currentVersion = "new-shadow")
        val before = snapshot()
        val zip = writeZip(File(store.partialDir(), "pack.zip"), linkedMapOf(
            "tiles.mbtiles" to "normal entry",
            "../vnew-shadow/tiles.mbtiles" to "must-not-overwrite",
        ))
        assertFailure(finish(zip, "new", null), "pack_archive_path_invalid")
        assertThat(snapshot()).isEqualTo(before)
        assertThat(store.packDir("new").exists()).isFalse()
        assertThat(store.partialDir().exists()).isFalse()
    }

    @Test
    fun `worker download refuses hard-linked archives before touching adopted or outside bytes`() = runTest {
        val zip = seedScratchSafety()
        val base = zip.parentFile!!.parentFile!!
        Files.move(zip.toPath(), File(tempDir, "retained-hardlink.zip").toPath())
        val sentinels = listOf("current", "previous").flatMap { version ->
            components.keys.map { path -> File(base, "v$version/$path") }
        } + File(tempDir, "outside/user.bin")
        sentinels.forEach { sentinel ->
            Files.createLink(zip.toPath(), sentinel.toPath())
            try {
                assertUnsafeScratch(zip)
            } finally {
                Files.deleteIfExists(zip.toPath())
            }
        }
    }

    @Test
    fun `worker extraction refuses hard-linked new payload before touching adopted or outside bytes`() = runTest {
        val zip = seedScratchSafety()
        val base = zip.parentFile!!.parentFile!!
        val sentinels = listOf("current", "previous").flatMap { version ->
            components.keys.map { path -> File(base, "v$version/$path") }
        } + File(tempDir, "outside/user.bin")
        val alias = File(base, "vnew/tiles.mbtiles")
        sentinels.forEach { sentinel ->
            if (!zip.exists()) writeZip(zip, components)
            Files.createLink(alias.toPath(), sentinel.toPath())
            try {
                val before = completeSnapshot()
                val phases = mutableListOf<DownloadProgress.Phase>()
                val outcome = finish(zip, "new", manifestJson("new")) { phases.add(it) }
                assertThat(completeSnapshot()).isEqualTo(withoutTrees(before, "mappacks/au-sa/.partial"))
                assertFailure(outcome, "pack_target_invalid")
                assertThat(phases).isEmpty()
            } finally {
                Files.deleteIfExists(alias.toPath())
            }
        }
    }

    @Test
    fun `worker download and tail refuse root nested and archive links preserving complete tree`() = runTest {
        val zip = seedScratchSafety()
        val scratch = zip.parentFile!!
        val base = scratch.parentFile!!
        val destinations = listOf(File(base, "vcurrent"), File(base, "vprevious"), base,
            File(tempDir, "outside"), File(tempDir, "missing"))
        val retained = File(tempDir, "retained-scratch")
        Files.move(scratch.toPath(), retained.toPath())
        destinations.forEach { destination ->
            Files.createSymbolicLink(scratch.toPath(), destination.toPath())
            assertUnsafeScratch(zip)
            Files.delete(scratch.toPath())
        }
        Files.move(retained.toPath(), scratch.toPath())
        destinations.forEach { destination ->
            val link = File(scratch, "nested/redirect")
            Files.createSymbolicLink(link.toPath(), destination.toPath())
            assertUnsafeScratch(zip)
            val before = completeSnapshot()
            val directFailure = runCatching { deletePartialScratch(scratch) }.exceptionOrNull()
            assertThat(directFailure?.message).isEqualTo("pack_scratch_invalid")
            assertThat(completeSnapshot()).isEqualTo(before)
            Files.delete(link.toPath())
        }
        val retainedZip = File(tempDir, "retained.zip")
        Files.move(zip.toPath(), retainedZip.toPath())
        listOf(File(base, "current.json"), File(base, "previous.json"),
            File(base, "vcurrent/tiles.mbtiles"), File(base, "vprevious/search.db"),
            File(tempDir, "outside/user.bin"), File(tempDir, "missing")).forEach { destination ->
            Files.createSymbolicLink(zip.toPath(), destination.toPath())
            assertUnsafeScratch(zip)
            Files.delete(zip.toPath())
        }
        Files.move(retainedZip.toPath(), zip.toPath())
    }

    @Test
    fun `worker download and tail refuse substituted parents and non-directory scratch`() = runTest {
        val zip = seedScratchSafety()
        val scratch = zip.parentFile!!
        val base = scratch.parentFile!!
        listOf(base, base.parentFile!!).forEach { directory ->
            val retained = File(tempDir, "retained")
            Files.move(directory.toPath(), retained.toPath())
            Files.createSymbolicLink(directory.toPath(), File(tempDir, "outside").toPath())
            assertUnsafeScratch(zip)
            Files.delete(directory.toPath())
            directory.writeText("not a directory")
            assertUnsafeScratch(zip)
            Files.delete(directory.toPath())
            Files.move(retained.toPath(), directory.toPath())
        }
        val retained = File(tempDir, "retained-scratch")
        Files.move(scratch.toPath(), retained.toPath())
        scratch.writeText("not a directory")
        assertUnsafeScratch(zip)
        Files.delete(scratch.toPath())
        Files.move(retained.toPath(), scratch.toPath())
        Files.move(zip.toPath(), File(tempDir, "retained.zip").toPath())
        zip.mkdirs()
        assertUnsafeScratch(zip)
    }

    @Test
    fun `worker late scratch substitution refuses publication and never escalates cleanup`() = runTest {
        val zip = seedScratchSafety()
        val link = File(zip.parentFile, "nested/redirect")
        listOf(false, true).forEach { throwFailure ->
            var before: Map<String, TreeEntry>? = null
            val phases = mutableListOf<DownloadProgress.Phase>()
            val outcome = finish(zip, "new", null) { phase ->
                phases.add(phase)
                if (phase == DownloadProgress.Phase.VERIFYING) {
                    Files.createSymbolicLink(link.toPath(), File(tempDir, "outside").toPath())
                    before = completeSnapshot()
                    if (throwFailure) throw Error("late private failure")
                }
            }
            assertThat(completeSnapshot()).isEqualTo(checkNotNull(before))
            assertFailure(outcome, "pack_scratch_invalid")
            assertThat(phases).containsExactly(DownloadProgress.Phase.EXTRACTING, DownloadProgress.Phase.VERIFYING).inOrder()
            assertThat(store.readCurrent()?.version).isEqualTo("current")
            assertThat(store.readPrevious()?.version).isEqualTo("previous")
            Files.delete(link.toPath())
        }
    }

    @Test
    fun `worker generic download failure deletes only owned scratch and unreferenced attempt`() = runTest {
        seedScratchSafety()
        val before = completeSnapshot()
        assertThat(cleanupDownloadFailure(Error("bounded download failure"), "new", store, installer))
            .isEqualTo("bounded download failure")
        assertThat(completeSnapshot()).isEqualTo(withoutTrees(before, "mappacks/au-sa/.partial", "mappacks/au-sa/vnew"))
    }

    @Test
    fun `worker download boundary keeps cancel resume bytes then cleans owned scratch on normal refusal`() = runTest {
        val zip = seedScratchSafety()
        val archive = zip.readBytes()
        val partialSize = archive.size / 2
        zip.writeBytes(archive.copyOfRange(0, partialSize))
        val before = completeSnapshot()
        val requests = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val range = request.headers[HttpHeaders.Range]
                    requests.add(range)
                    when {
                        requests.size == 1 -> respond(archive.copyOfRange(partialSize, archive.size), HttpStatusCode.PartialContent)
                        range != null -> respond(ByteArray(0), HttpStatusCode.RequestedRangeNotSatisfiable)
                        else -> respond(archive, HttpStatusCode.OK)
                    }
                }
            }
        }
        try {
            val cancel = CancellationException("bounded download cancel")
            val escaped = runCatching {
                downloadPackToScratch("https://example.com/pack.zip", PackDownloader(client), store) { _, _ -> throw cancel }
            }.exceptionOrNull()
            assertThat(escaped).isInstanceOf(CancellationException::class.java)
            val expected = before.toMutableMap()
            val zipPath = "mappacks/au-sa/.partial/pack.zip"
            expected[zipPath] = before.getValue(zipPath).copy(bytes = archive.toList())
            assertThat(completeSnapshot()).isEqualTo(expected)
            assertThat(runCatching { cleanupDownloadFailure(checkNotNull(escaped), "new", store, installer) }.exceptionOrNull())
                .isInstanceOf(CancellationException::class.java)
            assertThat(completeSnapshot()).isEqualTo(expected)
            assertThat(downloadPackToScratch("https://example.com/pack.zip", PackDownloader(client), store)).isEqualTo(zip)
            assertThat(zip.readBytes().toList()).isEqualTo(archive.toList())
            assertThat(requests).containsExactlyElementsIn(listOf("bytes=$partialSize-", "bytes=${archive.size}-", null)).inOrder()
            // 416 deliberately replaces the owned archive identity, and nothing else.
            expected[zipPath] = expected.getValue(zipPath).copy(identity = completeSnapshot().getValue(zipPath).identity)
            assertThat(completeSnapshot()).isEqualTo(expected)
            assertFailure(finish(zip, "current", null), "pack_target_referenced")
            assertThat(completeSnapshot()).isEqualTo(withoutTrees(expected, "mappacks/au-sa/.partial"))
        } finally {
            client.close()
        }
    }

    private suspend fun seedScratchSafety(): File {
        seedReferences()
        val zip = zipInScratch()
        val base = zip.parentFile!!.parentFile!!
        File(zip.parentFile, "nested/empty").mkdirs()
        listOf(File(zip.parentFile, "resume.marker"), File(base, "manifest-cache.json"),
            File(base, "current.json.tmp.abandoned"), File(base, "unknown/user.bin"),
            File(base, "vnew/incomplete"), File(tempDir, "outside/user.bin")).forEach { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(0, -1, 42, 1))
        }
        return zip
    }

    private suspend fun assertUnsafeScratch(zip: File) {
        val before = completeSnapshot()
        var requests = 0
        var progress = 0
        val client = HttpClient(MockEngine) {
            engine { addHandler { requests++; respond("must not reach transport".toByteArray(), HttpStatusCode.OK) } }
        }
        try {
            repeat(2) {
                val failure = runCatching {
                    downloadPackToScratch("https://example.com/private-pack.zip", PackDownloader(client), store) { _, _ -> progress++ }
                }.exceptionOrNull()
                assertThat(completeSnapshot()).isEqualTo(before)
                assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
                assertThat(failure?.message).isEqualTo("pack_scratch_invalid")
                val message = cleanupDownloadFailure(Error("private download payload"), "new", store, installer)
                assertThat(workerFailureData(message).getString(MapPackDownloadWorker.KEY_RESULT_ERROR)).isEqualTo("pack_scratch_invalid")
                assertThat(completeSnapshot()).isEqualTo(before)
                val phases = mutableListOf<DownloadProgress.Phase>()
                assertFailure(finish(zip, "new", null) { phases.add(it) }, "pack_scratch_invalid")
                assertThat(phases).isEmpty()
                assertThat(completeSnapshot()).isEqualTo(before)
                store.cleanupIfEmpty()
                assertThat(completeSnapshot()).isEqualTo(before)
            }
            assertThat(requests).isEqualTo(0)
            assertThat(progress).isEqualTo(0)
        } finally {
            client.close()
        }
    }

    private fun withoutTrees(before: Map<String, TreeEntry>, vararg roots: String) =
        before.filterKeys { path -> roots.none { path == it || path.startsWith("$it/") } }

    private suspend fun finish(
        zip: File,
        version: String,
        rawManifest: String?,
        onPhase: suspend (DownloadProgress.Phase) -> Unit = {},
    ) = installDownloadedPack(zip, version, rawManifest, extractor, store, installer, onPhase)

    private fun assertFailure(outcome: PackInstaller.Outcome, message: String) {
        assertThat(outcome).isEqualTo(PackInstaller.Outcome.VerificationFailed(message))
        assertThat(workerFailureData((outcome as PackInstaller.Outcome.VerificationFailed).message)
            .getString(MapPackDownloadWorker.KEY_RESULT_ERROR)).isEqualTo(message)
    }

    private suspend fun seedReferences(currentVersion: String = "current") {
        listOf(currentVersion, "previous").forEach { version ->
            components.keys.forEach { path ->
                File(store.packDir(version), path).apply {
                    parentFile!!.mkdirs()
                    writeText("sentinel-$version-$path")
                }
            }
        }
        store.writeCurrent(installed(currentVersion))
        store.writePrevious(installed("previous"))
    }

    private fun installed(version: String) = InstalledPack(
        version, "AU-SA", Instant.parse("2026-01-01T00:00:00Z"), 10,
        "tiles.mbtiles", "search.db", "routing/valhalla_tiles.tar", "metadata-sentinel-$version",
    )

    private fun zipInScratch() = writeZip(File(store.partialDir(), "pack.zip"), components)

    private fun writeZip(file: File, entries: Map<String, String>): File {
        file.parentFile!!.mkdirs()
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun manifestJson(version: String, routingHash: String = sha256(components.getValue("routing/valhalla_tiles.tar").toByteArray())): String {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val manifest = PackManifest(
            packVersion = version,
            region = Region.AU_SA,
            bbox = Bbox(129.0, -38.0, 141.0, -26.0),
            generatedAt = at,
            osmSource = OsmSource("fixture", "https://example.com", at),
            minAppVersion = "0.1.0",
            minAndroidSdk = 26,
            components = PackComponents(
                TileComponent("mbtiles", "openmaptiles", 0, 14, "tiles.mbtiles", 1, sha256(components.getValue("tiles.mbtiles").toByteArray())),
                RoutingComponent("valhalla", "auto", "routing/valhalla_tiles.tar", 1, routingHash),
                SearchComponent("sqlite", "search.db", 1, sha256(components.getValue("search.db").toByteArray())),
            ),
            totalSizeBytes = 3,
        )
        return Json.encodeToString(PackManifest.serializer(), manifest)
    }

    private fun snapshot(dir: File = store.packDir("").parentFile!!): Map<String, List<Byte>> =
        dir.walkTopDown().filter { it.isFile && ".partial" !in it.relativeTo(dir).path.split(File.separatorChar) }
            .associate { it.relativeTo(dir).path to it.readBytes().toList() }

    private data class TreeEntry(
        val kind: String,
        val bytes: List<Byte> = emptyList(),
        val link: String? = null,
        val identity: String? = null,
    )

    // Independent of snapshot(): include .partial, metadata, outside/user bytes,
    // empty directories and dangling links; neither walk nor attributes follow links.
    private fun completeSnapshot(): Map<String, TreeEntry> = Files.walk(tempDir.toPath()).use { paths ->
        paths.iterator().asSequence().associate { path ->
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val entry = when {
                attributes.isSymbolicLink -> TreeEntry("link", link = Files.readSymbolicLink(path).toString())
                attributes.isDirectory -> TreeEntry("directory")
                attributes.isRegularFile -> TreeEntry("file", bytes = Files.readAllBytes(path).toList())
                else -> TreeEntry("other")
            }
            tempDir.toPath().relativize(path).toString() to entry.copy(identity = attributes.fileKey()?.toString())
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
