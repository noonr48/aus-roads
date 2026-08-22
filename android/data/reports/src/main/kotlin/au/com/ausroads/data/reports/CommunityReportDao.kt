/*
 * Room DAO for the reports table. v0.5 foundation: simple CRUD with a Flow for
 * reactive reads plus expiry sweep. Mirrors PinDao's conventions (suspend Int
 * returns for Room 2.6.1 + KSP 2.x).
 */
package au.com.ausroads.data.reports

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CommunityReportDao {
    @Query("SELECT * FROM reports ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CommunityReport>>

    @Query("SELECT * FROM reports WHERE id = :id")
    suspend fun findById(id: Long): CommunityReport?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(report: CommunityReport): Long

    // Room 2.6.1 + KSP 2.x: suspend fun returning Unit triggers "unexpected jvm
    // signature V" in Room's annotation processor. Return Int (rows affected) instead.
    @Update
    suspend fun update(report: CommunityReport): Int

    @Delete
    suspend fun delete(report: CommunityReport): Int

    @Query("DELETE FROM reports WHERE expires_at IS NOT NULL AND expires_at < :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int
}
