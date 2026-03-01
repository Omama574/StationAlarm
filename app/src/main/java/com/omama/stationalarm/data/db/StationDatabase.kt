package com.omama.stationalarm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ActiveStationEntity::class], version = 1, exportSchema = false)
abstract class StationDatabase : RoomDatabase() {
    abstract fun activeStationDao(): ActiveStationDao

    companion object {
        @Volatile
        private var INSTANCE: StationDatabase? = null

        fun getDatabase(context: Context): StationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StationDatabase::class.java,
                    "station_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
