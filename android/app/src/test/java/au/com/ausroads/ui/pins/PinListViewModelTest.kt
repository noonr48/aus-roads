package au.com.ausroads.ui.pins

import app.cash.turbine.test
import au.com.ausroads.data.pins.Pin
import au.com.ausroads.data.pins.PinRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun `initial pins list is empty`() = runTest {
        viewModel.pins.test {
            assertThat(awaitItem()).isEmpty()
        }
    }

    @Test
    fun `addPinAt adds a pin`() = runTest {
        viewModel.pins.test {
            assertThat(awaitItem()).isEmpty()
            viewModel.addPinAt(longitude = 138.6, latitude = -34.9)
            val pins = awaitItem()
            assertThat(pins).hasSize(1)
            assertThat(pins.first().lat).isEqualTo(-34.9)
            assertThat(pins.first().lon).isEqualTo(138.6)
        }
    }

    @Test
    fun `delete removes a pin`() = runTest {
        val pin = Pin(
            id = 1,
            name = "Test Pin",
            lat = -34.9,
            lon = 138.6,
            createdAt = Instant.fromEpochSeconds(0),
        )
        fakeRepository.add(pin)

        viewModel.pins.test {
            assertThat(awaitItem()).hasSize(1)
            viewModel.delete(pin)
            assertThat(awaitItem()).isEmpty()
        }
    }

    private class FakePinRepository : PinRepository {
        private val pins = MutableStateFlow<List<Pin>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<Pin>> = pins

        override suspend fun add(pin: Pin): Long {
            val id = nextId++
            pins.value = pins.value + pin.copy(id = id)
            return id
        }

        override suspend fun update(pin: Pin): Int {
            val index = pins.value.indexOfFirst { it.id == pin.id }
            if (index == -1) return 0
            val mutable = pins.value.toMutableList()
            mutable[index] = pin
            pins.value = mutable
            return 1
        }

        override suspend fun delete(pin: Pin): Int {
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
