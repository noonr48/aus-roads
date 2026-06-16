/*
 * Room DAO for the installed_packs table. Reactive reads + insert-on-replace
 * (region is the primary key; an upsert on a region's pack replaces the row).
 */
package au.com.ausroads.data.pack

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledPackDao {
    @Query("SELECT * FROM installed_packs")
    fun observeAll(): Flow<List<InstalledPackEntity>>

    @Query("SELECT * FROM installed_packs WHERE region_code = :regionCode LIMIT 1")
    fun observeByRegion(regionCode: String): Flow<InstalledPackEntity?>

    @Query("SELECT * FROM installed_packs WHERE region_code = :regionCode LIMIT 1")
    suspend fun findByRegion(regionCode: String): InstalledPackEntity?

    // Room 2.6.1 + KSP 2.x: suspend fun returning Unit triggers "unexpected jvm
    // signature V" in Room's annotation processor. Return non-Unit types instead.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pack: InstalledPackEntity): Long

    @Query("DELETE FROM installed_packs WHERE region_code = :regionCode")
    suspend fun deleteForRegion(regionCode: String): Int
}
