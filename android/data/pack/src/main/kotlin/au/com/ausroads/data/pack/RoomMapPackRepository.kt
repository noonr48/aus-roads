/*
 * Room-backed MapPackRepository. Bound by Hilt in :app/AppModule.
 */
package au.com.ausroads.data.pack

import au.com.ausroads.core.model.Region
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class RoomMapPackRepository @Inject constructor(
    private val dao: InstalledPackDao,
) : MapPackRepository {

    override fun observeAll(): Flow<List<InstalledPackEntity>> = dao.observeAll()

    override fun observeByRegion(region: Region): Flow<InstalledPackEntity?> =
        dao.observeByRegion(region.code)

    override suspend fun upsert(pack: InstalledPackEntity) = dao.upsert(pack)

    override suspend fun deleteForRegion(region: Region): Int = dao.deleteForRegion(region.code)
}
