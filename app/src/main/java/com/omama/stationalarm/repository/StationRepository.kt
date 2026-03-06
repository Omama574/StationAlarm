package com.omama.stationalarm.repository

import android.content.Context
import androidx.lifecycle.asLiveData
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.SavedPlace
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object StationRepository {

    private lateinit var appContext: Context
    private lateinit var database: StationDatabase

    lateinit var activeStationsFlow: Flow<List<ActiveStation>>

    /** Exposes all saved places as a real-time flow for the Map tab UI. */
    lateinit var savedPlacesFlow: Flow<List<SavedPlace>>

    private val _distancesFlow = MutableStateFlow<Map<String, Double>>(emptyMap())
    val distancesFlow: StateFlow<Map<String, Double>> = _distancesFlow.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        database = StationDatabase.getDatabase(appContext)
        StationData.initialize(appContext)

        activeStationsFlow = database.activeStationDao().getAllActiveStations().map { entities ->
            entities.map { it.toDomainModel() }
        }

        savedPlacesFlow = database.savedPlaceDao().getAllSavedPlaces().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    /**
     * Resolves a station by ID. First checks the hardcoded railway list,
     * then falls back to user-saved places (for custom geofence locations).
     * Called from LocationService background threads — must be suspend.
     */
    suspend fun getStationById(id: String): Station? {
        return StationData.getStationById(id)
            ?: database.savedPlaceDao().getById(id)?.toDomainModel()?.toStation()
    }

    /** Synchronous variant for non-suspend callers that already know the type. */
    fun getStationByIdSync(id: String): Station? = StationData.getStationById(id)

    fun searchStations(query: String): List<Station> = StationData.searchStations(query)
    fun getAllStations(): List<Station> = StationData.getAllStations()

    // ── Saved Places CRUD ──────────────────────────────────────────────────

    fun saveFavoritePlace(place: SavedPlace) {
        CoroutineScope(Dispatchers.IO).launch {
            database.savedPlaceDao().insert(place.toEntity())
            Logger.log("SAVED_PLACE_ADDED", extra = "id=${place.id} name=${place.name}")
        }
    }

    fun deleteFavoritePlace(placeId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            database.savedPlaceDao().delete(placeId)
            Logger.log("SAVED_PLACE_DELETED", extra = "id=$placeId")
        }
    }

    fun updateFavoritePlace(placeId: String, name: String, radiusKm: Double, notes: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            database.savedPlaceDao().update(placeId, name, radiusKm, notes)
        }
    }

    suspend fun getAllSavedPlacesList(): List<SavedPlace> {
        return database.savedPlaceDao().getAllSavedPlacesList().map { it.toDomainModel() }
    }

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
                radiusLevel1M = (activeStation.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (activeStation.alertDistanceKm * 1000).toFloat()
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

    /** Transition a station to ALERTING status. Called when device enters alert radius. */
    fun markAlerting(stationId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            database.activeStationDao().updateStatus(stationId, "ALERTING")
            Logger.log("STATUS_CHANGE", stationId, "ALERTING")
        }
    }

    /** Transition a station to DISMISSED. Called by user pressing Dismiss. Deletes the row. */
    fun dismissStation(stationId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            database.activeStationDao().delete(stationId)
            GeofenceManager.removeGeofencesForStation(appContext, stationId)
            Logger.log("STATION_DISMISSED_AND_DELETED", stationId)
        }
    }

    fun updateStationDistance(stationId: String, distanceKm: Double) {
        val currentDistances = _distancesFlow.value.toMutableMap()
        currentDistances[stationId] = distanceKm
        _distancesFlow.value = currentDistances
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
                    radiusLevel1M = (active.radiusLevel1Km * 1000).toFloat(),
                    alertDistanceM = (active.alertDistanceKm * 1000).toFloat()
                )
            }
        }
    }
}