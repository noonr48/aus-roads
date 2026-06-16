/*
 * PinRepository — interface so the UI layer (PinsViewModel) never depends on Room.
 * RoomPinRepository is the single production implementation, injected by Hilt.
 */
package au.com.ausroads.data.pins

import kotlinx.coroutines.flow.Flow

interface PinRepository {
    fun observeAll(): Flow<List<Pin>>
    suspend fun add(pin: Pin): Long
    suspend fun update(pin: Pin): Int
    suspend fun delete(pin: Pin): Int
    suspend fun clear(): Int
}
