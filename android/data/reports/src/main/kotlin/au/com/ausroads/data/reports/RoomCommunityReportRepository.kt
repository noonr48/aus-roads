/*
 * Room-backed CommunityReportRepository. Bound by Hilt in :app/ReportsModule.
 */
package au.com.ausroads.data.reports

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Singleton
class RoomCommunityReportRepository @Inject constructor(
    private val dao: CommunityReportDao,
) : CommunityReportRepository {

    override fun observeAll(): Flow<List<CommunityReport>> = dao.observeAll()

    override suspend fun findById(id: Long): CommunityReport? = dao.findById(id)

    override suspend fun add(report: CommunityReport): Long = dao.insert(report)

    override suspend fun update(report: CommunityReport) = dao.update(report)

    override suspend fun delete(report: CommunityReport) = dao.delete(report)

    override suspend fun deleteExpired(now: Instant) =
        dao.deleteExpired(now.toEpochMilliseconds())
}
