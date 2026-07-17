package au.com.ausroads

import app.cash.turbine.test
import au.com.ausroads.core.common.PackRuntimeGenerationGate
import au.com.ausroads.di.PackRuntimeCoordinator
import au.com.ausroads.offline.download.state.InstalledPack
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test

class AusRoadsAppPackInitializationTest {

    @Test
    fun `reconciled null emits startup fallback then observes later replacements`() = runTest {
        val initialized = MutableStateFlow(false)
        val installed = MutableStateFlow<InstalledPack?>(null)

        reconciledPacks(initialized, installed).test {
            expectNoEvents()

            // Reconciliation can clear a stale descriptor without changing the
            // nullable installed value. Readiness itself must trigger startup.
            initialized.value = true
            assertThat(awaitItem()).isNull()

            val first = installedPack("2026-07-16", "2026-07-16T10:00:00Z")
            installed.value = first
            assertThat(awaitItem()).isEqualTo(first)

            // Same-version repair still has a new identity via installedAt.
            val repaired = installedPack("2026-07-16", "2026-07-16T11:00:00Z")
            installed.value = repaired
            assertThat(awaitItem()).isEqualTo(repaired)

            installed.value = null
            assertThat(awaitItem()).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `latest installed identity at reconciliation boundary is replayed`() = runTest {
        val initialized = MutableStateFlow(false)
        val installed = MutableStateFlow<InstalledPack?>(
            installedPack("2026-07-15", "2026-07-15T10:00:00Z"),
        )

        reconciledPacks(initialized, installed).test {
            expectNoEvents()
            val latest = installedPack("2026-07-16", "2026-07-16T10:00:00Z")
            installed.value = latest
            initialized.value = true

            assertThat(awaitItem()).isEqualTo(latest)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `map preparation failure preserves the prior usable generation`() {
        assertFailedPreparationPreservesGeneration(failAt = "map")
    }

    @Test
    fun `routing preparation failure preserves the prior usable generation`() {
        assertFailedPreparationPreservesGeneration(failAt = "routing")
    }

    @Test
    fun `search preparation failure preserves the prior usable generation`() {
        assertFailedPreparationPreservesGeneration(failAt = "search")
    }

    @Test
    fun `successful activation publishes only after both candidates commit`() {
        val old = installedPack("old", "2026-07-16T10:00:00Z")
        val next = installedPack("next", "2026-07-16T11:00:00Z")
        val coordinator = PackRuntimeCoordinator().apply { publish(old) }
        val events = mutableListOf<String>()
        var routingGeneration = "old"
        var searchGeneration = "old"

        val result = activatePackGeneration(
            pack = next,
            prepareRouting = { events += "prepare-routing"; TestCandidate(events, "routing") },
            prepareSearch = { events += "prepare-search"; TestCandidate(events, "search") },
            activateRouting = { routingGeneration = "next"; events += "activate-routing" },
            activateSearch = { searchGeneration = "next"; events += "activate-search" },
            publish = { pack -> events += "publish"; coordinator.publish(pack) },
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(routingGeneration).isEqualTo("next")
        assertThat(searchGeneration).isEqualTo("next")
        assertThat(coordinator.activePack.value).isEqualTo(next)
        assertThat(events).containsExactly(
            "prepare-routing",
            "prepare-search",
            "activate-routing",
            "activate-search",
            "publish",
            "close-search",
            "close-routing",
        ).inOrder()
    }

    @Test
    fun `generation reader cannot cross between component commits and publication`() {
        val next = installedPack("next", "2026-07-16T11:00:00Z")
        val coordinator = PackRuntimeCoordinator()
        val routingGeneration = AtomicReference("old")
        val searchGeneration = AtomicReference("old")
        val readerStarted = CountDownLatch(1)
        val observation = AtomicReference<List<String>>()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = activatePackGeneration(
                pack = next,
                prepareRouting = { TestCandidate(mutableListOf(), "routing") },
                prepareSearch = { TestCandidate(mutableListOf(), "search") },
                activateRouting = {
                    routingGeneration.set("next")
                    executor.execute {
                        readerStarted.countDown()
                        PackRuntimeGenerationGate.read {
                            observation.set(
                                listOf(
                                    routingGeneration.get(),
                                    searchGeneration.get(),
                                    coordinator.activePack.value?.version.orEmpty(),
                                ),
                            )
                        }
                    }
                    assertThat(readerStarted.await(2, TimeUnit.SECONDS)).isTrue()
                },
                activateSearch = { searchGeneration.set("next") },
                publish = coordinator::publish,
            )

            assertThat(result.isSuccess).isTrue()
            executor.shutdown()
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue()
            assertThat(observation.get()).containsExactly("next", "next", "next").inOrder()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertFailedPreparationPreservesGeneration(failAt: String) {
        val old = installedPack("old", "2026-07-16T10:00:00Z")
        val next = installedPack("next", "2026-07-16T11:00:00Z")
        val coordinator = PackRuntimeCoordinator().apply { publish(old) }
        val events = mutableListOf<String>()
        var routingGeneration = "old"
        var searchGeneration = "old"

        val result = activatePackGeneration(
            pack = next,
            prepareMap = {
                events += "prepare-map"
                if (failAt == "map") error("map failed")
            },
            prepareRouting = {
                events += "prepare-routing"
                if (failAt == "routing") error("routing failed")
                TestCandidate(events, "routing")
            },
            prepareSearch = {
                events += "prepare-search"
                if (failAt == "search") error("search failed")
                TestCandidate(events, "search")
            },
            activateRouting = { routingGeneration = "next"; events += "activate-routing" },
            activateSearch = { searchGeneration = "next"; events += "activate-search" },
            publish = { pack -> events += "publish"; coordinator.publish(pack) },
        )

        assertThat(result.isFailure).isTrue()
        assertThat(routingGeneration).isEqualTo("old")
        assertThat(searchGeneration).isEqualTo("old")
        assertThat(coordinator.activePack.value).isEqualTo(old)
        assertThat(events).doesNotContain("activate-routing")
        assertThat(events).doesNotContain("activate-search")
        assertThat(events).doesNotContain("publish")
        when (failAt) {
            "map" -> assertThat(events).containsExactly("prepare-map")
            "routing" -> assertThat(events).containsExactly("prepare-map", "prepare-routing").inOrder()
            "search" -> assertThat(events).containsExactly(
                "prepare-map",
                "prepare-routing",
                "prepare-search",
                "close-routing",
            ).inOrder()
        }
    }

    private fun installedPack(version: String, installedAt: String) = InstalledPack(
        version = version,
        regionCode = "AU-SA",
        installedAt = Instant.parse(installedAt),
        totalSizeBytes = 100L,
        tilesPath = "tiles.mbtiles",
        manifestSha256 = "manifest",
    )

    private class TestCandidate(
        private val events: MutableList<String>,
        private val name: String,
    ) : AutoCloseable {
        override fun close() {
            events += "close-$name"
        }
    }
}
