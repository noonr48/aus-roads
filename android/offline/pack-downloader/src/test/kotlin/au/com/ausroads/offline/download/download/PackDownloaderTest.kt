package au.com.ausroads.offline.download.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondBadRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat
import java.io.File

class PackDownloaderTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir("pack-downloader-test")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `download writes response body to target file`() = runTest {
        val body = "hello tile data"
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = body.toByteArray(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength to listOf(body.length.toString())),
                    )
                }
            }
        }
        val downloader = PackDownloader(client)
        val target = File(tempDir, "tiles.mbtiles")

        val result = downloader.download("https://example.com/tiles", target) { _, _ -> }

        assertThat(result).isEqualTo(target)
        assertThat(target.readText()).isEqualTo(body)
    }

    @Test
    fun `download reports progress via callback`() = runTest {
        val body = "abcdefghij"
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = body.toByteArray(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength to listOf(body.length.toString())),
                    )
                }
            }
        }
        val downloader = PackDownloader(client)
        val target = File(tempDir, "data.bin")
        val progressCalls = mutableListOf<Pair<Long, Long?>>()

        downloader.download("https://example.com/data", target) { downloaded, total ->
            progressCalls.add(downloaded to total)
        }

        assertThat(progressCalls).isNotEmpty()
        assertThat(progressCalls.last().first).isEqualTo(body.length.toLong())
        assertThat(progressCalls.last().second).isEqualTo(body.length.toLong())
    }

    @Test
    fun `download sends Range header when file already exists`() = runTest {
        val existingContent = "partial"
        val resumeContent = "-rest"
        var receivedRangeHeader: String? = null

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    receivedRangeHeader = request.headers[HttpHeaders.Range]
                    respond(
                        content = resumeContent.toByteArray(),
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf(HttpHeaders.ContentLength to listOf(resumeContent.length.toString())),
                    )
                }
            }
        }
        val downloader = PackDownloader(client)
        val target = File(tempDir, "resume.bin")
        target.writeText(existingContent)

        downloader.download("https://example.com/resume", target) { _, _ -> }

        assertThat(receivedRangeHeader).isEqualTo("bytes=${existingContent.length}-")
        assertThat(target.readText()).isEqualTo(existingContent + resumeContent)
    }

    @Test
    fun `download restarts from scratch on 416 Range Not Satisfiable`() = runTest {
        var callCount = 0
        val fullContent = "full-data"

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    callCount++
                    if (callCount == 1) {
                        respond(
                            content = ByteArray(0),
                            status = HttpStatusCode.RequestedRangeNotSatisfiable,
                        )
                    } else {
                        respond(
                            content = fullContent.toByteArray(),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentLength to listOf(fullContent.length.toString())),
                        )
                    }
                }
            }
        }
        val downloader = PackDownloader(client)
        val target = File(tempDir, "restart.bin")
        target.writeText("old-partial")

        downloader.download("https://example.com/restart", target) { _, _ -> }

        assertThat(callCount).isEqualTo(2)
        assertThat(target.readText()).isEqualTo(fullContent)
    }
}
