package com.omama.stationalarm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ActiveStationEntity::class, SavedPlaceEntity::class],
    version = 4,
    exportSchema = true
)
abstract class StationDatabase : RoomDatabase() {
    abstract fun activeStationDao(): ActiveStationDao
    abstract fun savedPlaceDao(): SavedPlaceDao

    companion object {
        @Volatile
        private var INSTANCE: StationDatabase? = null

        /** Adds the saved_places table; active_stations unchanged. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_places (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        radiusKm REAL NOT NULL,
                        notes TEXT,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** Adds notify, vibrate, and sound columns to saved_places. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE saved_places ADD COLUMN notify INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE saved_places ADD COLUMN vibrate INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE saved_places ADD COLUMN sound INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getDatabase(context: Context): StationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StationDatabase::class.java,
                    "station_database"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
