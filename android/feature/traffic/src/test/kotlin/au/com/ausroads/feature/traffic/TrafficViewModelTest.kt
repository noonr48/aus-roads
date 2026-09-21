package au.com.ausroads.feature.traffic

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.traffic.provider.EventType
import au.com.ausroads.traffic.provider.FetchResult
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.traffic.provider.LiveTrafficProvider
import au.com.ausroads.traffic.provider.Severity
import au.com.ausroads.traffic.provider.SourceType
import au.com.ausroads.traffic.provider.TrafficProviderAuthenticationException
import au.com.ausroads.traffic.provider.TrafficProviderHttpException
import au.com.ausroads.traffic.provider.TrafficGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `events start empty`() = runTest {
        val provider = FakeTrafficProvider()
        val viewModel = TrafficViewModel(setOf(provider))
        assertThat(viewModel.events.value).isEmpty()
    }

    @Test
    fun `fetchTraffic populates events from multiple providers`() = runTest {
        val provider1 = FakeTrafficProvider(
            regionCode = "PROVIDER-1",
            eventsToReturn = listOf(makeEvent("1", EventType.ROADWORKS)),
            closuresToReturn = listOf(makeEvent("2", EventType.CLOSURE)),
        )
        val provider2 = FakeTrafficProvider(
            regionCode = "PROVIDER-2",
            eventsToReturn = listOf(makeEvent("3", EventType.INCIDENT)),
        )
        val viewModel = TrafficViewModel(setOf(provider1, provider2))
        viewModel.fetchTraffic()
        advanceUntilIdle()
        assertThat(viewModel.events.value).hasSize(3)
    }

    @Test
    fun `individual provider failure does not stop others`() = runTest {
        val failing = FakeTrafficProvider(regionCode = "FAIL", shouldFail = true)
        val working = FakeTrafficProvider(
            regionCode = "OK",
            eventsToReturn = listOf(makeEvent("1", EventType.ROADWORKS)),
        )
        val viewModel = TrafficViewModel(setOf(failing, working))
        viewModel.fetchTraffic()
        advanceUntilIdle()
        assertThat(viewModel.events.value).hasSize(1)
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun `successful empty provider prevents all providers unavailable error`() = runTest {
        val emptyButWorking = FakeTrafficProvider(regionCode = "EMPTY")
        val failing = FakeTrafficProvider(regionCode = "FAIL", shouldFail = true)
        val viewModel = TrafficViewModel(setOf(emptyButWorking, failing))

        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.events.value).isEmpty()
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun `error sets error state when all providers fail`() = runTest {
        val provider = FakeTrafficProvider(shouldFail = true)
        val viewModel = TrafficViewModel(setOf(provider))
        viewModel.fetchTraffic()
        advanceUntilIdle()
        assertThat(viewModel.error.value).isNotNull()
        assertThat(viewModel.lastUpdated.value).isNull()
    }

    @Test
    fun `retry clears failure after the provider recovers`() = runTest {
        val provider = FakeTrafficProvider(
            eventsToReturn = listOf(makeEvent("recovered", EventType.INCIDENT)),
            shouldFail = true,
        )
        val viewModel = TrafficViewModel(setOf(provider))

        viewModel.fetchTraffic()
        advanceUntilIdle()
        assertThat(viewModel.error.value).isEqualTo(TrafficFailure.AllProvidersUnavailable)

        provider.shouldFail = false
        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.error.value).isNull()
        assertThat(viewModel.events.value).hasSize(1)
    }

    @Test
    fun `Not Modified responses preserve each provider's prior events`() = runTest {
        val provider = FakeTrafficProvider(
            eventsToReturn = listOf(makeEvent("event-v1", EventType.INCIDENT)),
            closuresToReturn = listOf(makeEvent("closure-v1", EventType.CLOSURE)),
            eventsEtag = "events-v1",
            closuresEtag = "closures-v1",
        )
        val viewModel = TrafficViewModel(setOf(provider))

        viewModel.fetchTraffic()
        advanceUntilIdle()
        assertThat(viewModel.events.value.map { it.id })
            .containsExactly("event-v1", "closure-v1")

        provider.eventsToReturn = emptyList()
        provider.closuresToReturn = emptyList()
        provider.eventsNotModified = true
        provider.closuresNotModified = true
        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.events.value.map { it.id })
            .containsExactly("event-v1", "closure-v1")
        assertThat(provider.eventIfNoneMatches).containsExactly(null, "events-v1").inOrder()
        assertThat(provider.closureIfNoneMatches).containsExactly(null, "closures-v1").inOrder()
    }

    @Test
    fun `successful empty response clears prior events`() = runTest {
        val provider = FakeTrafficProvider(
            eventsToReturn = listOf(makeEvent("stale", EventType.INCIDENT)),
            eventsEtag = "events-v1",
        )
        val viewModel = TrafficViewModel(setOf(provider))
        viewModel.fetchTraffic()
        advanceUntilIdle()

        provider.eventsToReturn = emptyList()
        provider.eventsEtag = "events-v2"
        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.events.value).isEmpty()
    }

    @Test
    fun `transient provider failure preserves its last successful events`() = runTest {
        val unstable = FakeTrafficProvider(
            regionCode = "UNSTABLE",
            eventsToReturn = listOf(makeEvent("last-good", EventType.INCIDENT)),
        )
        val working = FakeTrafficProvider(
            regionCode = "WORKING",
            eventsToReturn = listOf(makeEvent("working-v1", EventType.ROADWORKS)),
        )
        val viewModel = TrafficViewModel(setOf(unstable, working))
        viewModel.fetchTraffic()
        advanceUntilIdle()

        unstable.shouldFail = true
        working.eventsToReturn = listOf(makeEvent("working-v2", EventType.ROADWORKS))
        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.events.value.map { it.id })
            .containsExactly("last-good", "working-v2")
        assertThat(viewModel.error.value).isNull()
    }

    @Test
    fun `authentication rejection is region-specific while another provider remains usable`() = runTest {
        val nsw = FakeTrafficProvider(
            regionCode = "AU-NSW",
            failure = TrafficProviderAuthenticationException("AU-NSW"),
        )
        val sa = FakeTrafficProvider(
            regionCode = "AU-SA",
            eventsToReturn = listOf(makeEvent("sa-current", EventType.INCIDENT)),
        )
        val viewModel = TrafficViewModel(setOf(nsw, sa))

        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.error.value)
            .isEqualTo(TrafficFailure.InvalidCredentials(regionCode = "AU-NSW"))
        assertThat(viewModel.events.value.map { it.id }).containsExactly("sa-current")
    }

    @Test
    fun `authentication rejection retains that provider snapshot and refreshes other providers`() = runTest {
        val nsw = FakeTrafficProvider(
            regionCode = "AU-NSW",
            eventsToReturn = listOf(makeEvent("nsw-last-good", EventType.ROADWORKS)),
        )
        val vic = FakeTrafficProvider(
            regionCode = "AU-VIC",
            eventsToReturn = listOf(makeEvent("vic-v1", EventType.INCIDENT)),
        )
        val viewModel = TrafficViewModel(setOf(nsw, vic))
        viewModel.fetchTraffic()
        advanceUntilIdle()

        nsw.failure = TrafficProviderAuthenticationException("AU-NSW")
        vic.eventsToReturn = listOf(makeEvent("vic-v2", EventType.INCIDENT))
        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.events.value.map { it.id })
            .containsExactly("nsw-last-good", "vic-v2")
        assertThat(viewModel.error.value)
            .isEqualTo(TrafficFailure.InvalidCredentials(regionCode = "AU-NSW"))
    }

    @Test
    fun `upstream server failure is not reported as invalid credentials`() = runTest {
        val provider = FakeTrafficProvider(
            regionCode = "AU-NSW",
            failure = TrafficProviderHttpException(regionCode = "AU-NSW", statusCode = 500),
        )
        val viewModel = TrafficViewModel(setOf(provider))

        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.error.value).isEqualTo(TrafficFailure.AllProvidersUnavailable)
    }

    @Test
    fun `credential guidance clears after the rejected provider recovers`() = runTest {
        val provider = FakeTrafficProvider(
            regionCode = "AU-VIC",
            eventsToReturn = listOf(makeEvent("vic-recovered", EventType.INCIDENT)),
            failure = TrafficProviderAuthenticationException("AU-VIC"),
        )
        val viewModel = TrafficViewModel(setOf(provider))
        viewModel.fetchTraffic()
        advanceUntilIdle()
        assertThat(viewModel.error.value)
            .isEqualTo(TrafficFailure.InvalidCredentials(regionCode = "AU-VIC"))

        provider.failure = null
        viewModel.fetchTraffic()
        advanceUntilIdle()

        assertThat(viewModel.error.value).isNull()
        assertThat(viewModel.events.value.map { it.id }).containsExactly("vic-recovered")
    }

    @Test
    fun `stopPolling cancels an active fetch and prevents later polls`() = runTest {
        val provider = FakeTrafficProvider(suspendEventFetch = true)
        val viewModel = TrafficViewModel(setOf(provider))

        viewModel.startPolling()
        runCurrent()
        assertThat(provider.eventFetchCount).isEqualTo(1)
        assertThat(viewModel.isLoading.value).isTrue()

        viewModel.stopPolling()
        runCurrent()
        assertThat(provider.eventFetchCancelled).isTrue()
        assertThat(viewModel.isLoading.value).isFalse()

        advanceTimeBy(TrafficViewModel.POLL_INTERVAL.inWholeMilliseconds * 2)
        runCurrent()
        assertThat(provider.eventFetchCount).isEqualTo(1)
    }

    private fun makeEvent(id: String, type: EventType) = LiveTrafficEvent(
        id = id,
        source = "traffic-sa",
        sourceType = SourceType.OFFICIAL,
        region = Region.AU_SA,
        type = type,
        severity = Severity.MEDIUM,
        description = "Test event $id",
        geometry = TrafficGeometry.Point(138.6, -34.9),
        startTime = null,
        endTime = null,
        attributes = emptyMap(),
        attribution = "Test",
    )
}

private class FakeTrafficProvider(
    regionCode: String = "TEST",
    var eventsToReturn: List<LiveTrafficEvent> = emptyList(),
    var closuresToReturn: List<LiveTrafficEvent> = emptyList(),
    var shouldFail: Boolean = false,
    var eventsEtag: String? = null,
    var closuresEtag: String? = null,
    var eventsNotModified: Boolean = false,
    var closuresNotModified: Boolean = false,
    var suspendEventFetch: Boolean = false,
    var failure: Exception? = null,
) : LiveTrafficProvider {
    override val regionCode = regionCode
    override val displayName = "Test Provider ($regionCode)"
    override val supportedBbox = Bbox(-180.0, -90.0, 180.0, 90.0)
    val eventIfNoneMatches = mutableListOf<String?>()
    val closureIfNoneMatches = mutableListOf<String?>()
    var eventFetchCount = 0
    var eventFetchCancelled = false

    override suspend fun fetchEvents(bbox: Bbox?, ifNoneMatch: String?): FetchResult {
        eventFetchCount += 1
        eventIfNoneMatches += ifNoneMatch
        failure?.let { throw it }
        if (shouldFail) throw RuntimeException("Network error")
        if (suspendEventFetch) {
            try {
                awaitCancellation()
            } finally {
                eventFetchCancelled = true
            }
        }
        return FetchResult(
            events = eventsToReturn,
            serverTimestamp = null,
            cacheMaxAge = 5.minutes,
            etag = eventsEtag,
            lastModified = null,
            notModified = eventsNotModified,
        )
    }

    override suspend fun fetchClosures(bbox: Bbox?, ifNoneMatch: String?): FetchResult {
        closureIfNoneMatches += ifNoneMatch
        failure?.let { throw it }
        if (shouldFail) throw RuntimeException("Network error")
        return FetchResult(
            events = closuresToReturn,
            serverTimestamp = null,
            cacheMaxAge = 5.minutes,
            etag = closuresEtag,
            lastModified = null,
            notModified = closuresNotModified,
        )
    }
}
