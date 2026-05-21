package com.omama.stationalarm.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveStationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(activeStation: ActiveStationEntity)

    @Query("DELETE FROM active_stations WHERE stationId = :stationId")
    suspend fun delete(stationId: String)

    @Query("SELECT * FROM active_stations")
    fun getAllActiveStations(): Flow<List<ActiveStationEntity>>

    @Query("SELECT * FROM active_stations")
    suspend fun getAllActiveStationsList(): List<ActiveStationEntity>

    @Query("SELECT * FROM active_stations WHERE stationId = :stationId LIMIT 1")
    suspend fun getStationById(stationId: String): ActiveStationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM active_stations WHERE stationId = :stationId)")
    suspend fun isActive(stationId: String): Boolean

    @Query("SELECT status FROM active_stations WHERE stationId = :stationId LIMIT 1")
    suspend fun getStatus(stationId: String): String?

    @Query("UPDATE active_stations SET status = :status WHERE stationId = :stationId")
    suspend fun updateStatus(stationId: String, status: String)

    @Query("UPDATE active_stations SET status = 'ALERTING' WHERE stationId = :stationId AND status = 'MONITORING'")
    suspend fun markAlertingFromMonitoring(stationId: String)

    @Query("UPDATE active_stations SET status = 'MONITORING' WHERE status = 'ALERTING'")
    suspend fun resetAllAlertingToMonitoring(): Int

    @Query("UPDATE active_stations SET alertDistanceKm = :radius, notify = :notify, vibrate = :vibrate, sound = :sound, customReminder = :reminder, sendReminder = :sendReminder WHERE stationId = :stationId")
    suspend fun updateSettings(stationId: String, radius: Double, notify: Boolean, vibrate: Boolean, sound: Boolean, reminder: String?, sendReminder: Boolean)

    @Query("UPDATE active_stations SET stationName = :name WHERE stationId = :stationId")
    suspend fun updateStationName(stationId: String, name: String)

    @Query("UPDATE active_stations SET lastTriggeredAt = :timestamp WHERE stationId = :stationId")
    suspend fun setLastTriggeredAt(stationId: String, timestamp: Long)

}
