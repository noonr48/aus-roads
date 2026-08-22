/*
 * CommunityReportRepository — interface so the UI layer never depends on Room.
 * RoomCommunityReportRepository is the single production implementation,
 * injected by Hilt. LOCAL-ONLY: no method here performs networking.
 */
package au.com.ausroads.data.reports

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

interface CommunityReportRepository {
    fun observeAll(): Flow<List<CommunityReport>>
    suspend fun findById(id: Long): CommunityReport?
    suspend fun add(report: CommunityReport): Long
    suspend fun update(report: CommunityReport): Int
    suspend fun delete(report: CommunityReport): Int
    suspend fun deleteExpired(now: Instant): Int
}
