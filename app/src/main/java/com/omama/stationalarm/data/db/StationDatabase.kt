package com.omama.stationalarm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ActiveStationEntity::class],
    version = 6,
    exportSchema = true
)
abstract class StationDatabase : RoomDatabase() {
    abstract fun activeStationDao(): ActiveStationDao

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

        /** Persists lat, lon, and stationName in active_stations so custom map
         *  alarms survive process death and device reboot. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE active_stations ADD COLUMN lat REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE active_stations ADD COLUMN lon REAL NOT NULL DEFAULT 0.0")
                database.execSQL("ALTER TABLE active_stations ADD COLUMN stationName TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Drops the now-unused saved_places table; the active_stations row is
         *  the single source of truth for both railway and custom alarms.
         *  Adds lastTriggeredAt so the UI can show "Last triggered" timestamps. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS saved_places")
                database.execSQL("ALTER TABLE active_stations ADD COLUMN lastTriggeredAt INTEGER")
            }
        }

        fun getDatabase(context: Context): StationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StationDatabase::class.java,
                    "station_database"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    // Version 1 was only ever a dev/emulator schema and has no written
                    // 1→2 migration. Wipe those legacy DBs instead of crashing on upgrade.
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
