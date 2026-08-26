package au.com.ausroads.offline.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Locks the MapPackDownloadWorker failure contract without WorkManager machinery:
 * every failing exit must drop the .partial scratch artifact (a surviving corrupt
 * partial is Range-resumed onto poisoned bytes forever), and every failing exit
 * except cancellation must surface a non-blank KEY_RESULT_ERROR payload.
 */
class WorkerFailureContractTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("worker-failure-test")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `error payload survives Error-class throwables`() {
        // OOM-class Error with its own message passes through verbatim...
        val oom = OutOfMemoryError("simulated heap exhaustion")
        assertThat(workerErrorMessageOrRethrow(oom)).isEqualTo("simulated heap exhaustion")
        // ...and still yields NON-BLANK payload when the throwable has no message,
        // so the UI never shows an idle state with nothing to retry against.
        assertThat(workerErrorMessageOrRethrow(Error()))
            .isEqualTo(DOWNLOAD_ERROR_FALLBACK)
        assertThat(workerErrorMessageOrRethrow(IllegalStateException("")))
            .isEqualTo(DOWNLOAD_ERROR_FALLBACK)
        assertThat(workerErrorMessageOrRethrow(IllegalStateException("   ")))
            .isEqualTo(DOWNLOAD_ERROR_FALLBACK)
    }

    @Test
    fun `ordinary exception messages pass through`() {
        val boom = IllegalStateException("connection reset mid-download")
        assertThat(workerErrorMessageOrRethrow(boom))
            .isEqualTo("connection reset mid-download")
    }

    @Test
    fun `cancellation is rethrown and NOT swallowed`() {
        val cancel = CancellationException("work stopped by user")
        var escaped: Throwable? = null
        try {
            workerErrorMessageOrRethrow(cancel)
        } catch (t: CancellationException) {
            escaped = t
        }

        // Same instance escapes untouched: mapping it to Result.failure would mark
        // ordinary cancelled stops FAILED with bogus errors.
        assertThat(escaped).isSameInstanceAs(cancel)
    }

    @Test
    fun `partial scratch deletion removes the downloaded zip artifact`() {
        val scratch = File(tempDir, ".partial").apply { mkdirs() }
        File(scratch, "pack.zip").writeText("poison-bytes")
        val chunkDir = File(scratch, "pack.zip.tmp-part")
        chunkDir.mkdirs()
        File(chunkDir, "chunk.bin").writeText("half-written")

        deletePartialScratch(scratch)

        assertThat(scratch.exists()).isFalse()
        assertThat(tempDir.exists()).isTrue()
    }

    @Test
    fun `partial scratch deletion tolerates a missing scratch dir`() {
        val scratch = File(tempDir, ".never-created")

        deletePartialScratch(scratch)

        assertThat(scratch.exists()).isFalse()
    }
}
