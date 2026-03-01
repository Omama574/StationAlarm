package com.omama.stationalarm.repository

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.data.db.StationDatabase
import com.omama.stationalarm.data.db.toDomainModel
import com.omama.stationalarm.data.db.toEntity
import com.omama.stationalarm.geofence.GeofenceManager
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object StationRepository {

    private lateinit var appContext: Context
    private lateinit var database: StationDatabase

    lateinit var activeStationsLiveData: LiveData<List<ActiveStation>>
    lateinit var activeStationsFlow: Flow<List<ActiveStation>>

    fun initialize(context: Context) {
        appContext = context.applicationContext
        database = StationDatabase.getDatabase(appContext)
        StationData.initialize(appContext)

        activeStationsFlow = database.activeStationDao().getAllActiveStations().map { entities ->
            entities.map { it.toDomainModel() }
        }
        activeStationsLiveData = activeStationsFlow.asLiveData(Dispatchers.IO)
    }

    fun getStationById(id: String): Station? = StationData.getStationById(id)
    fun searchStations(query: String): List<Station> = StationData.searchStations(query)
    fun getAllStations(): List<Station> = StationData.getAllStations()

    fun addActiveStation(activeStation: ActiveStation) {
        CoroutineScope(Dispatchers.IO).launch {
            database.activeStationDao().insert(activeStation.toEntity())
            
            GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = activeStation.stationId,
                radiusLevel5M = (activeStation.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (activeStation.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (activeStation.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (activeStation.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (activeStation.radiusLevel1Km * 1000).toFloat()
            )
        }
    }

    fun removeActiveStation(stationId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            database.activeStationDao().delete(stationId)
            GeofenceManager.removeGeofencesForStation(appContext, stationId)
            Logger.log("STATION_REMOVED", stationId)
        }
    }

    fun updateStationDistance(stationId: String, distanceKm: Double) {
        // Not persisting distance to DB to save I/O thrashing.
        // It resides in memory in tracking loop, not DB.
    }

    suspend fun getAllActiveStationsList(): List<ActiveStation> {
        return database.activeStationDao().getAllActiveStationsList().map { it.toDomainModel() }
    }

    suspend fun isActive(stationId: String): Boolean {
        return database.activeStationDao().isActive(stationId)
    }

    fun reRegisterAllGeofences() {
        CoroutineScope(Dispatchers.IO).launch {
            val activeList = getAllActiveStationsList()
            for (active in activeList) {
                GeofenceManager.addGeofencesForStation(
                    context = appContext,
                    stationId = active.stationId,
                    radiusLevel5M = (active.radiusLevel5Km * 1000).toFloat(),
                    radiusLevel4M = (active.radiusLevel4Km * 1000).toFloat(),
                    radiusLevel3M = (active.radiusLevel3Km * 1000).toFloat(),
                    radiusLevel2M = (active.radiusLevel2Km * 1000).toFloat(),
                    radiusLevel1M = (active.radiusLevel1Km * 1000).toFloat()
                )
            }
        }
    }
}