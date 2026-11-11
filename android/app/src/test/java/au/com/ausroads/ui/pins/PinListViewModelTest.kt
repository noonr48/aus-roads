package au.com.ausroads.ui.pins

import app.cash.turbine.test
import au.com.ausroads.data.pins.Pin
import au.com.ausroads.data.pins.PinRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
class PinListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepository: FakePinRepository
    private lateinit var viewModel: PinListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakePinRepository()
        viewModel = PinListViewModel(fakeRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading until the first repository emission`() = runTest {
        val repository = FakePinRepository()
        val firstEmission = CompletableDeferred<List<Pin>>()
        repository.observation = flow { emit(firstEmission.await()) }
        val loadingViewModel = PinListViewModel(repository)
        val pin = savedPin()

        loadingViewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(PinListUiState.Loading)
            firstEmission.complete(listOf(pin))
            assertThat(awaitItem()).isEqualTo(PinListUiState.Content(listOf(pin)))
        }
    }

    @Test
    fun `an empty repository emission is represented as true empty content`() = runTest {
        assertThat(viewModel.uiState.value).isEqualTo(PinListUiState.Content(emptyList()))
    }

    @Test
    fun `repository read failure is represented as a recoverable error`() = runTest {
        val repository = FakePinRepository()
        val readFailure = CompletableDeferred<Throwable>()
        repository.observation = flow<List<Pin>> { throw readFailure.await() }
        val failingViewModel = PinListViewModel(repository)

        failingViewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(PinListUiState.Loading)
            readFailure.complete(IllegalStateException("simulated read failure"))
            assertThat(awaitItem()).isEqualTo(PinListUiState.ReadError)
        }
    }

    @Test
    fun `retry resubscribes and recovers after a repository read failure`() = runTest {
        val repository = FakePinRepository()
        val readFailure = CompletableDeferred<Throwable>()
        repository.observation = flow<List<Pin>> { throw readFailure.await() }
        val retryingViewModel = PinListViewModel(repository)
        val retryEmission = CompletableDeferred<List<Pin>>()
        val pin = savedPin()

        retryingViewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(PinListUiState.Loading)
            readFailure.complete(IllegalStateException("simulated read failure"))
            assertThat(awaitItem()).isEqualTo(PinListUiState.ReadError)

            repository.observation = flow { emit(retryEmission.await()) }
            retryingViewModel.retrySavedPins()
            assertThat(awaitItem()).isEqualTo(PinListUiState.Loading)
            assertThat(repository.observeCalls).isEqualTo(2)

            retryEmission.complete(listOf(pin))
            assertThat(awaitItem()).isEqualTo(PinListUiState.Content(listOf(pin)))
        }
    }

    @Test
    fun `read cancellation is rethrown without becoming a read error`() = runTest {
        val repository = FakePinRepository()
        val cancellation = CompletableDeferred<Unit>()
        repository.observation = flow {
            cancellation.await()
            throw CancellationException("cancel observation")
        }
        val cancellingViewModel = PinListViewModel(repository)
        val retryEmission = CompletableDeferred<List<Pin>>()
        val pin = savedPin()

        cancellingViewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(PinListUiState.Loading)
            cancellation.complete(Unit)
            expectNoEvents()
            assertThat(cancellingViewModel.uiState.value).isEqualTo(PinListUiState.Loading)

            repository.observation = flow { emit(retryEmission.await()) }
            cancellingViewModel.retrySavedPins()
            retryEmission.complete(listOf(pin))
            assertThat(awaitItem()).isEqualTo(PinListUiState.Content(listOf(pin)))
            assertThat(repository.observeCalls).isEqualTo(2)
        }
    }

    @Test
    fun `addPinAt adds a pin`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(PinListUiState.Content(emptyList()))
            viewModel.addPinAt(longitude = 138.6, latitude = -34.9)
            val pins = (awaitItem() as PinListUiState.Content).pins
            assertThat(pins).hasSize(1)
            assertThat(pins.first().lat).isEqualTo(-34.9)
            assertThat(pins.first().lon).isEqualTo(138.6)
        }
    }

    @Test
    fun `addPinAt reports repository failure`() = runTest {
        fakeRepository.throwOnAdd = true
        var failure: Throwable? = null

        viewModel.addPinAt(
            longitude = 138.6,
            latitude = -34.9,
            onError = { failure = it },
        )

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat((viewModel.uiState.value as PinListUiState.Content).pins).isEmpty()
    }

    @Test
    fun `addPinAt rejects invalid world coordinates before persistence`() = runTest {
        var failure: Throwable? = null

        viewModel.addPinAt(
            longitude = 138.6,
            latitude = 999.0,
            onError = { failure = it },
        )

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat((viewModel.uiState.value as PinListUiState.Content).pins).isEmpty()
    }

    @Test
    fun `update reports success only after repository changes the pin`() = runTest {
        val pin = savedPin()
        fakeRepository.add(pin)
        var updated = false

        viewModel.updatePin(
            pin = pin,
            newName = "Renamed pin",
            newColor = "#00838F",
            onUpdated = { updated = true },
        )

        assertThat(updated).isTrue()
        assertThat(fakeRepository.currentPins.single().name).isEqualTo("Renamed pin")
        assertThat(fakeRepository.currentPins.single().color).isEqualTo("#00838F")
    }

    @Test
    fun `update reports a missing row as failure`() = runTest {
        var updated = false
        var failure: Throwable? = null

        viewModel.updatePin(
            pin = savedPin(),
            newName = "Renamed pin",
            newColor = "#00838F",
            onUpdated = { updated = true },
            onError = { failure = it },
        )

        assertThat(updated).isFalse()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `update reports repository failure without changing the pin`() = runTest {
        val pin = savedPin()
        fakeRepository.add(pin)
        fakeRepository.updateFailure = IllegalStateException("simulated update failure")
        var updated = false
        var failure: Throwable? = null

        viewModel.updatePin(
            pin = pin,
            newName = "Renamed pin",
            newColor = "#00838F",
            onUpdated = { updated = true },
            onError = { failure = it },
        )

        assertThat(updated).isFalse()
        assertThat(failure).hasMessageThat().isEqualTo("simulated update failure")
        assertThat(fakeRepository.currentPins.single().name).isEqualTo(pin.name)
    }

    @Test
    fun `update rejects a blank name before persistence`() = runTest {
        var failure: Throwable? = null

        viewModel.updatePin(
            pin = savedPin(),
            newName = "   ",
            newColor = "#00838F",
            onError = { failure = it },
        )

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(fakeRepository.updateCalls).isEqualTo(0)
    }

    @Test
    fun `update cancellation is not converted to a failure callback`() = runTest {
        fakeRepository.updateFailure = CancellationException("cancel update")
        var callbackCount = 0

        viewModel.updatePin(
            pin = savedPin(),
            newName = "Renamed pin",
            newColor = "#00838F",
            onUpdated = { callbackCount++ },
            onError = { callbackCount++ },
        )

        assertThat(callbackCount).isEqualTo(0)
    }

    @Test
    fun `delete removes a pin`() = runTest {
        val pin = savedPin()
        fakeRepository.add(pin)
        var deleted = false

        viewModel.uiState.test {
            assertThat((awaitItem() as PinListUiState.Content).pins).hasSize(1)
            viewModel.delete(pin, onDeleted = { deleted = true })
            assertThat((awaitItem() as PinListUiState.Content).pins).isEmpty()
        }
        assertThat(deleted).isTrue()
    }

    @Test
    fun `delete reports a missing row as failure`() = runTest {
        var deleted = false
        var failure: Throwable? = null

        viewModel.delete(
            pin = savedPin(),
            onDeleted = { deleted = true },
            onError = { failure = it },
        )

        assertThat(deleted).isFalse()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `delete reports repository failure without removing the pin`() = runTest {
        val pin = savedPin()
        fakeRepository.add(pin)
        fakeRepository.deleteFailure = IllegalStateException("simulated delete failure")
        var deleted = false
        var failure: Throwable? = null

        viewModel.delete(
            pin = pin,
            onDeleted = { deleted = true },
            onError = { failure = it },
        )

        assertThat(deleted).isFalse()
        assertThat(failure).hasMessageThat().isEqualTo("simulated delete failure")
        assertThat(fakeRepository.currentPins).containsExactly(pin)
    }

    @Test
    fun `delete can be retried after a repository failure`() = runTest {
        val pin = savedPin()
        fakeRepository.add(pin)
        fakeRepository.deleteFailure = IllegalStateException("temporary delete failure")
        var failureCount = 0
        var deleted = false

        viewModel.delete(pin = pin, onError = { failureCount++ })
        fakeRepository.deleteFailure = null
        viewModel.delete(pin = pin, onDeleted = { deleted = true })

        assertThat(failureCount).isEqualTo(1)
        assertThat(deleted).isTrue()
        assertThat(fakeRepository.currentPins).isEmpty()
    }

    @Test
    fun `delete cancellation is not converted to a failure callback`() = runTest {
        fakeRepository.deleteFailure = CancellationException("cancel delete")
        var callbackCount = 0

        viewModel.delete(
            pin = savedPin(),
            onDeleted = { callbackCount++ },
            onError = { callbackCount++ },
        )

        assertThat(callbackCount).isEqualTo(0)
    }

    private fun savedPin() = Pin(
        id = 1,
        name = "Test Pin",
        lat = -34.9,
        lon = 138.6,
        createdAt = Instant.fromEpochSeconds(0),
    )

    private class FakePinRepository : PinRepository {
        private val pins = MutableStateFlow<List<Pin>>(emptyList())
        private var nextId = 1L
        var observation: Flow<List<Pin>> = pins
        var observeCalls: Int = 0
            private set
        var throwOnAdd: Boolean = false
        var updateFailure: Exception? = null
        var deleteFailure: Exception? = null
        var updateCalls: Int = 0
            private set
        val currentPins: List<Pin>
            get() = pins.value

        override fun observeAll(): Flow<List<Pin>> {
            observeCalls++
            return observation
        }

        override suspend fun add(pin: Pin): Long {
            if (throwOnAdd) throw IllegalStateException("simulated write failure")
            val id = nextId++
            pins.value = pins.value + pin.copy(id = id)
            return id
        }

        override suspend fun update(pin: Pin): Int {
            updateCalls++
            updateFailure?.let { throw it }
            val index = pins.value.indexOfFirst { it.id == pin.id }
            if (index == -1) return 0
            val mutable = pins.value.toMutableList()
            mutable[index] = pin
            pins.value = mutable
            return 1
        }

        override suspend fun delete(pin: Pin): Int {
            deleteFailure?.let { throw it }
            val sizeBefore = pins.value.size
            pins.value = pins.value.filter { it.id != pin.id }
            return if (pins.value.size < sizeBefore) 1 else 0
        }

        override suspend fun clear(): Int {
            val count = pins.value.size
            pins.value = emptyList()
            return count
        }
    }
}
