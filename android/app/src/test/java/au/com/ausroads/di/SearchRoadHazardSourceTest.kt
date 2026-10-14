package au.com.ausroads.di

import au.com.ausroads.offline.search.SearchRepository
import au.com.ausroads.offline.search.SpeedCameraPoint
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SearchRoadHazardSourceTest {
    private val repository = mockk<SearchRepository>()
    private val health = mockk<PlacesHealth>(relaxed = true)
    private val source = SearchRoadHazardSource(repository, health)

    @Before fun healthyQueries() {
        coEvery { repository.maxspeedNear(any(), any(), any()) } returns 60
        coEvery { repository.camerasNear(any(), any(), any(), any()) } returns emptyList()
    }

    @Test fun speedFailureIsUnknownNotStaleAndCannotHideHealthyCameras() = runTest {
        coEvery { repository.maxspeedNear(any(), any(), any()) } throws IOException("controlled query")
        coEvery { repository.camerasNear(any(), any(), any(), any()) } returns
            listOf(SpeedCameraPoint(-34.92, 138.62, 60, "Fixture"))
        val result = source.contextAt(-34.92, 138.62, 2_000.0)
        assertThat(result.speedLimitKmh).isNull()
        assertThat(result.cameras).hasSize(1)
        verify(exactly = 1) { health.failure(PlacesSeam.ROAD_SPEED_QUERY) }
        verify(exactly = 0) { health.success(PlacesSeam.ROAD_SPEED_QUERY) }
        verify(exactly = 1) { health.success(PlacesSeam.CAMERAS_QUERY) }
    }

    @Test fun cameraFailureClearsUnknownTargetsButPreservesTheHealthySpeed() = runTest {
        coEvery { repository.camerasNear(any(), any(), any(), any()) } throws IOException("controlled query")
        val result = source.contextAt(-34.92, 138.62, 2_000.0)
        assertThat(result.speedLimitKmh).isEqualTo(60)
        assertThat(result.cameras).isEmpty()
        verify(exactly = 1) { health.success(PlacesSeam.ROAD_SPEED_QUERY) }
        verify(exactly = 1) { health.failure(PlacesSeam.CAMERAS_QUERY) }
        verify(exactly = 0) { health.success(PlacesSeam.CAMERAS_QUERY) }
    }

    @Test fun optionalNoDataIsSuccessfulWithoutAQueryFailureAlarm() = runTest {
        coEvery { repository.maxspeedNear(any(), any(), any()) } returns null
        val result = source.contextAt(-34.92, 138.62, 2_000.0)
        assertThat(result.speedLimitKmh).isNull()
        assertThat(result.cameras).isEmpty()
        verify(exactly = 0) { health.failure(any()) }
        verify(exactly = 1) { health.success(PlacesSeam.ROAD_SPEED_QUERY) }
        verify(exactly = 1) { health.success(PlacesSeam.CAMERAS_QUERY) }
    }

    @Test fun cancellationIsNotUnknownDataOrASeamFailure() = runTest {
        coEvery { repository.maxspeedNear(any(), any(), any()) } throws CancellationException("controlled cancellation")
        val failure = runCatching { source.contextAt(-34.92, 138.62, 2_000.0) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        verify(exactly = 0) { health.failure(any()) }
        verify(exactly = 0) { health.success(any()) }
    }
}
