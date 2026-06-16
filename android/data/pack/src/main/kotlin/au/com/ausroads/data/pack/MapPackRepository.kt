/*
 * MapPackRepository — interface so the MapPackViewModel never depends on Room.
 * RoomMapPackRepository is the single production implementation, injected by Hilt.
 */
package au.com.ausroads.data.pack

import au.com.ausroads.core.model.Region
import kotlinx.coroutines.flow.Flow

interface MapPackRepository {
    fun observeAll(): Flow<List<InstalledPackEntity>>
    fun observeByRegion(region: Region): Flow<InstalledPackEntity?>
    suspend fun upsert(pack: InstalledPackEntity): Long
    suspend fun deleteForRegion(region: Region): Int
}
