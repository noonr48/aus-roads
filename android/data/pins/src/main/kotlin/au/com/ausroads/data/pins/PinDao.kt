/*
 * Room DAO for the pins table. v0.1.1: simple CRUD with a Flow for reactive reads.
 */
package au.com.ausroads.data.pins

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PinDao {
    @Query("SELECT * FROM pins ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Pin>>

    @Query("SELECT * FROM pins WHERE id = :id")
    suspend fun findById(id: Long): Pin?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pin: Pin): Long

    // Room 2.6.1 + KSP 2.x: suspend fun returning Unit triggers "unexpected jvm
    // signature V" in Room's annotation processor. Return Int (rows affected) instead.
    @Update
    suspend fun update(pin: Pin): Int

    @Delete
    suspend fun delete(pin: Pin): Int

    @Query("DELETE FROM pins")
    suspend fun clear(): Int
}
