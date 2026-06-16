package au.com.ausroads.data.routes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteHistoryDao {
    @Query("SELECT * FROM route_history ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<RouteHistoryEntity>>

    @Insert
    suspend fun insert(route: RouteHistoryEntity)

    @Query("DELETE FROM route_history WHERE id = :routeId")
    suspend fun deleteById(routeId: Long)

    @Query("DELETE FROM route_history")
    suspend fun clearAll()
}
