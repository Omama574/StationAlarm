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

    @Query("UPDATE active_stations SET status = :status WHERE stationId = :stationId")
    suspend fun updateStatus(stationId: String, status: String)

}
