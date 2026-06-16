package au.com.ausroads.feature.traffic

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import au.com.ausroads.traffic.provider.EventType
import au.com.ausroads.traffic.provider.FetchResult
import au.com.ausroads.traffic.provider.LiveTrafficEvent
import au.com.ausroads.traffic.provider.LiveTrafficProvider
import au.com.ausroads.traffic.provider.Severity
import au.com.ausroads.traffic.provider.SourceType
import au.com.ausroads.traffic.provider.TrafficGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
    fun `error sets error state when all providers fail`() = runTest {
        val provider = FakeTrafficProvider(shouldFail = true)
        val viewModel = TrafficViewModel(setOf(provider))
        viewModel.fetchTraffic()
        advanceUntilIdle()
        assertThat(viewModel.error.value).isNotNull()
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
    private val eventsToReturn: List<LiveTrafficEvent> = emptyList(),
    private val closuresToReturn: List<LiveTrafficEvent> = emptyList(),
    private val shouldFail: Boolean = false,
) : LiveTrafficProvider {
    override val regionCode = regionCode
    override val displayName = "Test Provider ($regionCode)"
    override val supportedBbox = Bbox(-180.0, -90.0, 180.0, 90.0)

    override suspend fun fetchEvents(bbox: Bbox?, ifNoneMatch: String?): FetchResult {
        if (shouldFail) throw RuntimeException("Network error")
        return FetchResult(eventsToReturn, null, 5.minutes, null, null)
    }

    override suspend fun fetchClosures(bbox: Bbox?, ifNoneMatch: String?): FetchResult {
        if (shouldFail) throw RuntimeException("Network error")
        return FetchResult(closuresToReturn, null, 5.minutes, null, null)
    }
}
