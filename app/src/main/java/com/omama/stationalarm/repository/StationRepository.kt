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
import kotlinx.coroutines.SupervisorJob

object StationRepository {

    private lateinit var appContext: Context
    private lateinit var database: StationDatabase
    
    // Centralized scope for all database writes to prevent fire-and-forget race conditions
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    lateinit var activeStationsFlow: Flow<List<ActiveStation>>

    /** Exposes all saved places as a real-time flow for the Map tab UI. */
    lateinit var savedPlacesFlow: Flow<List<SavedPlace>>

    private var _savedPlacesCache = mapOf<String, SavedPlace>()

    private val _distancesFlow = MutableStateFlow<Map<String, Double>>(emptyMap())
    val distancesFlow: StateFlow<Map<String, Double>> = _distancesFlow.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        database = StationDatabase.getDatabase(appContext)

        // Load stations.json on IO thread — avoids ~400ms main thread freeze on cold start.
        // searchStations() returns emptyList() until loaded (null-safe), which is fine since
        // the search debounce is 500ms and loading completes well within that window.
        repositoryScope.launch {
            StationData.initialize(appContext)
        }

        activeStationsFlow = database.activeStationDao().getAllActiveStations().map { entities ->
            entities.map { it.toDomainModel() }
        }

        savedPlacesFlow = database.savedPlaceDao().getAllSavedPlaces().map { entities ->
            entities.map { it.toDomainModel() }
        }

        // Keep a memory cache for synchronous lookups in LocationService/UI
        repositoryScope.launch {
            savedPlacesFlow.collect { list ->
                _savedPlacesCache = list.associateBy { it.id }
            }
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
    fun getStationByIdSync(id: String): Station? {
        return StationData.getStationById(id) ?: _savedPlacesCache[id]?.toStation()
    }

    fun searchStations(query: String): List<Station> = StationData.searchStations(query)
    fun getAllStations(): List<Station> = StationData.getAllStations()

    // ── Saved Places CRUD ──────────────────────────────────────────────────

    fun saveFavoritePlace(place: SavedPlace) {
        repositoryScope.launch {
            database.savedPlaceDao().insert(place.toEntity())
            Logger.log("SAVED_PLACE_ADDED", extra = "id=${place.id} name=${place.name}")
        }
    }

    fun deleteFavoritePlace(placeId: String) {
        repositoryScope.launch {
            database.savedPlaceDao().delete(placeId)
            Logger.log("SAVED_PLACE_DELETED", extra = "id=$placeId")
        }
    }

    suspend fun getAllSavedPlacesList(): List<SavedPlace> {
        return database.savedPlaceDao().getAllSavedPlacesList().map { it.toDomainModel() }
    }

    fun addActiveStation(activeStation: ActiveStation, customStation: Station? = null) {
        repositoryScope.launch {
            // Resolve lat/lon: prefer customStation, then hardcoded lookup, then active station itself
            val resolvedStation = customStation
                ?: StationData.getStationById(activeStation.stationId)

            val entityWithCoords = activeStation.copy(
                lat = resolvedStation?.lat ?: activeStation.lat,
                lon = resolvedStation?.lon ?: activeStation.lon,
                stationName = resolvedStation?.name ?: activeStation.stationName
            )

            if (customStation != null) {
                val place = SavedPlace(
                    id = customStation.id,
                    name = customStation.name,
                    lat = customStation.lat,
                    lon = customStation.lon,
                    radiusKm = activeStation.alertDistanceKm,
                    notes = "Custom alarmed location",
                    createdAt = System.currentTimeMillis()
                )
                database.savedPlaceDao().insert(place.toEntity())
            }
            database.activeStationDao().insert(entityWithCoords.toEntity())

            GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = entityWithCoords.stationId,
                radiusLevel5M = (entityWithCoords.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (entityWithCoords.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (entityWithCoords.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (entityWithCoords.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (entityWithCoords.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (entityWithCoords.alertDistanceKm * 1000).toFloat()
            )
        }
    }

    fun removeActiveStation(stationId: String) {
        repositoryScope.launch {
            database.activeStationDao().delete(stationId)
            GeofenceManager.removeGeofencesForStation(appContext, stationId)
            Logger.log("STATION_REMOVED", stationId)
        }
    }

    /** Transition a station to ALERTING status. Called when device enters alert radius. */
    fun markAlerting(stationId: String) {
        repositoryScope.launch {
            database.activeStationDao().updateStatus(stationId, "ALERTING")
            Logger.log("STATUS_CHANGE", stationId, "ALERTING")
        }
    }

    /** Transition a station to PAUSED after alarm fires + user dismisses.
     *  Keeps the row in DB so user can re-arm from the Alarms tab. */
    fun dismissStation(stationId: String) {
        repositoryScope.launch {
            database.activeStationDao().updateStatus(stationId, "PAUSED")
            GeofenceManager.removeGeofencesForStation(appContext, stationId)
            Logger.log("STATION_DISMISSED_TO_PAUSED", stationId)
        }
    }

    /** Manually pause a station (toggle OFF). Removes geofences but keeps alarm data. */
    fun pauseStation(stationId: String) {
        repositoryScope.launch {
            database.activeStationDao().updateStatus(stationId, "PAUSED")
            GeofenceManager.removeGeofencesForStation(appContext, stationId)
            Logger.log("STATION_PAUSED", stationId)
        }
    }

    /** Re-arm a paused station (toggle ON). Re-registers geofences and sets MONITORING. */
    fun rearmStation(stationId: String) {
        repositoryScope.launch {
            database.activeStationDao().updateStatus(stationId, "MONITORING")
            val station = database.activeStationDao().getStationById(stationId)?.toDomainModel() ?: return@launch
            GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = station.stationId,
                radiusLevel5M = (station.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (station.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (station.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (station.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (station.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (station.alertDistanceKm * 1000).toFloat()
            )
            Logger.log("STATION_REARMED", stationId)
        }
    }

    fun updateStationDistances(distances: Map<String, Double>) {
        val currentDistances = _distancesFlow.value.toMutableMap()
        currentDistances.putAll(distances)
        _distancesFlow.value = currentDistances
    }

    suspend fun getAllActiveStationsList(): List<ActiveStation> {
        return database.activeStationDao().getAllActiveStationsList().map { it.toDomainModel() }
    }

    suspend fun isActive(stationId: String): Boolean {
        return database.activeStationDao().isActive(stationId)
    }

    fun reRegisterAllGeofences() {
        repositoryScope.launch {
            val activeList = getAllActiveStationsList().filter { it.status != "PAUSED" }
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

    /** Reset an ALERTING station back to MONITORING (e.g., after device reboot). */
    suspend fun resetToMonitoring(stationId: String) {
        database.activeStationDao().updateStatus(stationId, "MONITORING")
        Logger.log("STATUS_CHANGE", stationId, "MONITORING (reset)")
    }

    /** Update settings of an active station in-place (radius, notification prefs, reminder). */
    fun updateActiveStationSettings(
        stationId: String,
        radius: Double,
        notify: Boolean,
        vibrate: Boolean,
        sound: Boolean,
        reminder: String?,
        sendReminder: Boolean
    ) {
        repositoryScope.launch {
            database.activeStationDao().updateSettings(stationId, radius, notify, vibrate, sound, reminder, sendReminder)
            // Re-register geofences with new radius
            GeofenceManager.removeGeofencesForStation(appContext, stationId)
            val updated = database.activeStationDao().getStationById(stationId)?.toDomainModel() ?: return@launch
            GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = updated.stationId,
                radiusLevel5M = (updated.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (updated.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (updated.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (updated.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (updated.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (updated.alertDistanceKm * 1000).toFloat()
            )
            Logger.log("STATION_SETTINGS_UPDATED", stationId, "radius=$radius notify=$notify vibrate=$vibrate sound=$sound")
        }
    }
}