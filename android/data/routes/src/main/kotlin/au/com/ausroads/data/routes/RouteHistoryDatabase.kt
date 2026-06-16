package au.com.ausroads.data.routes

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [RouteHistoryEntity::class], version = 1, exportSchema = false)
@TypeConverters(InstantConverters::class)
abstract class RouteHistoryDatabase : RoomDatabase() {
    abstract fun routeHistoryDao(): RouteHistoryDao

    companion object {
        const val DATABASE_NAME = "route_history.db"
    }
}
