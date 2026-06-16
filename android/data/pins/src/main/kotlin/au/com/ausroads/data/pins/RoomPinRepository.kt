/*
 * Room-backed PinRepository. Bound by Hilt in :app/AppModule.
 */
package au.com.ausroads.data.pins

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class RoomPinRepository @Inject constructor(
    private val dao: PinDao,
) : PinRepository {

    override fun observeAll(): Flow<List<Pin>> = dao.observeAll()

    override suspend fun add(pin: Pin): Long = dao.insert(pin)

    override suspend fun update(pin: Pin) = dao.update(pin)

    override suspend fun delete(pin: Pin) = dao.delete(pin)

    override suspend fun clear() = dao.clear()
}
