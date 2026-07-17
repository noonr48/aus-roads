package au.com.ausroads.routing.engine.valhalla

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ValhallaRoutingEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `discarded candidate deletes its private config`() {
        val config = configFile("discarded.json")
        val candidate = PreparedRoutingEngine(null, "candidate.tar", config)

        candidate.close()

        assertThat(config.exists()).isFalse()
    }

    @Test
    fun `activation transfers config ownership until generation is cleared`() {
        val config = configFile("active.json")
        val candidate = PreparedRoutingEngine(null, "active.tar", config)
        val generations = RoutingGenerationStore()

        generations.activate(candidate)
        candidate.close()

        assertThat(config.exists()).isTrue()
        assertThat(generations.read { it.configFile }).isEqualTo(config)
        generations.clear()
        assertThat(config.exists()).isFalse()
    }

    @Test
    fun `replacement waits for readers before deleting previous config`() {
        val oldConfig = configFile("old.json")
        val nextConfig = configFile("next.json")
        val generations = RoutingGenerationStore().apply {
            activate(PreparedRoutingEngine(null, "old.tar", oldConfig))
        }
        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val activationStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val reader = executor.submit {
                generations.read {
                    readerEntered.countDown()
                    check(releaseReader.await(2, TimeUnit.SECONDS))
                    check(oldConfig.exists())
                }
            }
            assertThat(readerEntered.await(2, TimeUnit.SECONDS)).isTrue()

            val activation = executor.submit {
                activationStarted.countDown()
                generations.activate(PreparedRoutingEngine(null, "next.tar", nextConfig))
            }
            assertThat(activationStarted.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(oldConfig.exists()).isTrue()

            releaseReader.countDown()
            reader.get(2, TimeUnit.SECONDS)
            activation.get(2, TimeUnit.SECONDS)

            assertThat(oldConfig.exists()).isFalse()
            assertThat(nextConfig.exists()).isTrue()
        } finally {
            releaseReader.countDown()
            generations.clear()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed candidate claim preserves active generation`() {
        val activeConfig = configFile("still-active.json")
        val rejectedConfig = configFile("rejected.json")
        val generations = RoutingGenerationStore().apply {
            activate(PreparedRoutingEngine(null, "active.tar", activeConfig))
        }
        val rejected = PreparedRoutingEngine(null, "rejected.tar", rejectedConfig).apply { close() }

        val result = runCatching { generations.activate(rejected) }

        assertThat(result.isFailure).isTrue()
        assertThat(generations.read { it.tilePath }).isEqualTo("active.tar")
        assertThat(activeConfig.exists()).isTrue()
        assertThat(rejectedConfig.exists()).isFalse()
        generations.clear()
    }

    private fun configFile(name: String): File = temporaryFolder.newFile(name).apply {
        writeText("{}")
        assertThat(setReadOnly()).isTrue()
    }
}
