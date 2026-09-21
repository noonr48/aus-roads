package au.com.ausroads.ui.map

import app.cash.turbine.test
import au.com.ausroads.data.routes.RouteHistoryDao
import au.com.ausroads.data.routes.RouteHistoryEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteHistoryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var dao: FakeRouteHistoryDao
    private lateinit var viewModel: RouteHistoryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dao = FakeRouteHistoryDao(listOf(savedRoute()))
        viewModel = RouteHistoryViewModel(dao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `delete reports success only after one history row is removed`() = runTest {
        var deleted = false

        viewModel.deleteRoute(savedRoute(), onDeleted = { deleted = true })

        assertThat(deleted).isTrue()
        assertThat(dao.routes.value).isEmpty()
    }

    @Test
    fun `save failure is nonfatal and retry persists the same route`() = runTest {
        dao.insertFailure = IllegalStateException("disk full")

        viewModel.saveRoute(
            originLat = -34.9212,
            originLon = 138.5995,
            destLat = -34.9285,
            destLon = 138.6007,
            distanceMeters = 1_200,
            durationSeconds = 600,
        )

        assertThat(viewModel.saveError.value).isTrue()
        assertThat(dao.routes.value).containsExactly(savedRoute())

        dao.insertFailure = null
        viewModel.retrySave()

        assertThat(viewModel.saveError.value).isFalse()
        assertThat(dao.routes.value).hasSize(2)
        assertThat(dao.routes.value.last().distanceMeters).isEqualTo(1_200)
    }

    @Test
    fun `delete reports a missing route as failure`() = runTest {
        dao.routes.value = emptyList()
        var deleted = false
        var failure: Throwable? = null

        viewModel.deleteRoute(
            route = savedRoute(),
            onDeleted = { deleted = true },
            onError = { failure = it },
        )

        assertThat(deleted).isFalse()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `delete reports storage failure and can be retried`() = runTest {
        dao.deleteFailure = IllegalStateException("temporary failure")
        var failures = 0
        var deleted = false

        viewModel.deleteRoute(savedRoute(), onError = { failures++ })
        dao.deleteFailure = null
        viewModel.deleteRoute(savedRoute(), onDeleted = { deleted = true })

        assertThat(failures).isEqualTo(1)
        assertThat(deleted).isTrue()
    }

    @Test
    fun `delete cancellation is not converted to a failure callback`() = runTest {
        dao.deleteFailure = CancellationException("cancel delete")
        var callbackCount = 0

        viewModel.deleteRoute(
            route = savedRoute(),
            onDeleted = { callbackCount++ },
            onError = { callbackCount++ },
        )

        assertThat(callbackCount).isEqualTo(0)
    }

    @Test
    fun `destination label prefers its name and otherwise exposes coordinates`() {
        assertThat(savedRoute().destinationLabel()).isEqualTo("Adelaide Central Market")
        assertThat(savedRoute().copy(destName = "").destinationLabel()).isEqualTo("-34.9285, 138.6007")
    }

    @Test
    fun `route history read failure is distinct from a true empty list and can retry`() = runTest {
        dao.observeFailure = IllegalStateException("temporary read failure")
        viewModel = RouteHistoryViewModel(dao)

        viewModel.recentRoutes.test {
            assertThat(awaitItem()).isEmpty()
            assertThat(viewModel.loadError.value).isTrue()

            dao.observeFailure = null
            viewModel.retryLoad()

            assertThat(awaitItem()).containsExactly(savedRoute())
            assertThat(viewModel.isLoading.value).isFalse()
            assertThat(viewModel.loadError.value).isFalse()
        }
    }

    private fun savedRoute() = RouteHistoryEntity(
        id = 7,
        originLat = -34.9212,
        originLon = 138.5995,
        destLat = -34.9285,
        destLon = 138.6007,
        originName = "Rundle Mall",
        destName = "Adelaide Central Market",
        distanceMeters = 1_200,
        durationSeconds = 600,
        createdAt = Instant.fromEpochSeconds(0),
    )

    private class FakeRouteHistoryDao(initialRoutes: List<RouteHistoryEntity>) : RouteHistoryDao {
        val routes = MutableStateFlow(initialRoutes)
        var deleteFailure: Exception? = null
        var insertFailure: Exception? = null
        var observeFailure: Exception? = null

        override fun observeRecent(limit: Int): Flow<List<RouteHistoryEntity>> = flow {
            observeFailure?.let { throw it }
            emitAll(routes)
        }

        override suspend fun insert(route: RouteHistoryEntity) {
            insertFailure?.let { throw it }
            routes.value += route
        }

        override suspend fun deleteById(routeId: Long): Int {
            deleteFailure?.let { throw it }
            val sizeBefore = routes.value.size
            routes.value = routes.value.filterNot { it.id == routeId }
            return sizeBefore - routes.value.size
        }

        override suspend fun clearAll() {
            routes.value = emptyList()
        }
    }
}
