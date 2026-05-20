package com.omama.stationalarm.repository

import android.content.Context
import androidx.lifecycle.asLiveData
import androidx.room.withTransaction
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /**
     * One-shot events for user-facing errors (e.g. max-alarms reached,
     * geofence registration failed). UI listens via the ViewModel and shows
     * these as snackbars. With DROP_OLDEST, a backlog of stale errors during
     * UI startup gets squeezed out in favour of the freshest one — the user
     * sees the most relevant message rather than chronological noise.
     */
    private val _errorEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

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
            // Pre-flight the max-alarms cap before we touch any DB rows. Without
            // this, we'd insert into active_stations, hit the cap inside
            // addGeofencesForStation, and leave an orphan row with no geofence.
            val existing = getAllActiveStationsList()
            val alreadyActive = existing.any { it.stationId == activeStation.stationId }
            if (!alreadyActive && existing.size >= GeofenceManager.MAX_ACTIVE_STATIONS) {
                _errorEvents.emit(
                    "You have reached the maximum of ${GeofenceManager.MAX_ACTIVE_STATIONS} alarms. " +
                            "Please delete some before adding more."
                )
                return@launch
            }

            // Resolve lat/lon: prefer customStation, then hardcoded lookup, then active station itself
            val resolvedStation = customStation
                ?: StationData.getStationById(activeStation.stationId)

            val entityWithCoords = activeStation.copy(
                lat = resolvedStation?.lat ?: activeStation.lat,
                lon = resolvedStation?.lon ?: activeStation.lon,
                stationName = resolvedStation?.name ?: activeStation.stationName
            )

            // Atomic DB insert: if the process dies between the two inserts, Room
            // rolls back so we never end up with a saved_place but no active_station
            // (or vice versa). Geofence registration stays outside the transaction
            // because it's GMS, not Room — its rollback is handled below.
            database.withTransaction {
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
            }

            val result = GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = entityWithCoords.stationId,
                radiusLevel8M = (entityWithCoords.radiusLevel8Km * 1000).toFloat(),
                radiusLevel7M = (entityWithCoords.radiusLevel7Km * 1000).toFloat(),
                radiusLevel6M = (entityWithCoords.radiusLevel6Km * 1000).toFloat(),
                radiusLevel5M = (entityWithCoords.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (entityWithCoords.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (entityWithCoords.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (entityWithCoords.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (entityWithCoords.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (entityWithCoords.alertDistanceKm * 1000).toFloat()
            )
            result.onFailure { e ->
                // Registration failed — roll back both rows in a single transaction
                // so the UI doesn't show a ghost alarm and the saved_place doesn't
                // leak as a phantom favourite.
                database.withTransaction {
                    database.activeStationDao().delete(entityWithCoords.stationId)
                    if (customStation != null) {
                        database.savedPlaceDao().delete(customStation.id)
                    }
                }
                val msg = e.message ?: "Failed to register alarm."
                _errorEvents.emit(msg)
                Logger.log("STATION_ADD_ROLLED_BACK", entityWithCoords.stationId, msg)
            }
        }
    }

    /**
     * Re-registers geofences for any active (non-PAUSED) station that may have
     * lost its GMS-side registration during process death between the DB insert
     * and the geofence call. `addGeofencesForStation` is idempotent (overwrites
     * by request ID), so calling it for already-registered stations is a no-op
     * besides a small refresh. Cheap to run on every cold start.
     */
    suspend fun reconcileOrphans() {
        val active = getAllActiveStationsList().filter { it.status == "MONITORING" }
        if (active.isEmpty()) return
        var reconciled = 0
        for (station in active) {
            val result = GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = station.stationId,
                radiusLevel8M = (station.radiusLevel8Km * 1000).toFloat(),
                radiusLevel7M = (station.radiusLevel7Km * 1000).toFloat(),
                radiusLevel6M = (station.radiusLevel6Km * 1000).toFloat(),
                radiusLevel5M = (station.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (station.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (station.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (station.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (station.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (station.alertDistanceKm * 1000).toFloat()
            )
            if (result.isSuccess) reconciled++
        }
        Logger.log("RECONCILED", extra = "$reconciled/${active.size} active stations refreshed at startup")
    }

    fun removeActiveStation(stationId: String) {
        repositoryScope.launch {
            database.activeStationDao().delete(stationId)
            GeofenceManager.removeGeofencesForStation(appContext, stationId)
            Logger.log("STATION_REMOVED", stationId)
        }
    }

    /** Transition a station to ALERTING status. Only valid from MONITORING — guards
     *  against late geofence events or duplicate markAlerting calls re-firing the
     *  alarm after the user has already dismissed (PAUSED) or while it is ringing. */
    fun markAlerting(stationId: String) {
        repositoryScope.launch {
            database.activeStationDao().markAlertingFromMonitoring(stationId)
            GeofenceManager.removeGeofencesForStation(appContext, stationId)
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
            val result = GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = station.stationId,
                radiusLevel8M = (station.radiusLevel8Km * 1000).toFloat(),
                radiusLevel7M = (station.radiusLevel7Km * 1000).toFloat(),
                radiusLevel6M = (station.radiusLevel6Km * 1000).toFloat(),
                radiusLevel5M = (station.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (station.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (station.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (station.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (station.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (station.alertDistanceKm * 1000).toFloat()
            )
            result.onSuccess {
                Logger.log("STATION_REARMED", stationId)
            }.onFailure { e ->
                // Re-registration failed — flip back to PAUSED so the toggle
                // doesn't lie about the alarm being armed.
                database.activeStationDao().updateStatus(stationId, "PAUSED")
                val msg = e.message ?: "Failed to re-arm alarm."
                _errorEvents.emit(msg)
                Logger.log("STATION_REARM_FAILED", stationId, msg)
            }
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

    /** True only when the station is armed (MONITORING or ALERTING), not PAUSED.
     *  Used by GeofenceBroadcastReceiver to ignore late-delivered events for
     *  paused stations whose geofence removal is still in flight. */
    suspend fun isArmed(stationId: String): Boolean {
        val status = database.activeStationDao().getStatus(stationId) ?: return false
        return status != "PAUSED"
    }

    fun reRegisterAllGeofences() {
        repositoryScope.launch {
            reRegisterAllGeofencesNow()
        }
    }

    /**
     * Suspend variant callable from WorkManager's CoroutineWorker without
     * spawning another coroutine. Returns true if every registration
     * succeeded, false if any failed (the caller — e.g. BootRestoreWorker —
     * decides whether to retry).
     */
    suspend fun reRegisterAllGeofencesNow(): Boolean {
        val activeList = getAllActiveStationsList().filter { it.status == "MONITORING" }
        var allOk = true
        for (active in activeList) {
            val result = GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = active.stationId,
                radiusLevel8M = (active.radiusLevel8Km * 1000).toFloat(),
                radiusLevel7M = (active.radiusLevel7Km * 1000).toFloat(),
                radiusLevel6M = (active.radiusLevel6Km * 1000).toFloat(),
                radiusLevel5M = (active.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (active.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (active.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (active.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (active.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (active.alertDistanceKm * 1000).toFloat()
            )
            if (result.isFailure) {
                allOk = false
                Logger.log("GEOFENCE_RE_REGISTER_FAILED", active.stationId, result.exceptionOrNull()?.message)
            }
        }
        return allOk
    }

    /** Reset an ALERTING station back to MONITORING (e.g., after device reboot). */
    suspend fun resetToMonitoring(stationId: String) {
        database.activeStationDao().updateStatus(stationId, "MONITORING")
        Logger.log("STATUS_CHANGE", stationId, "MONITORING (reset)")
    }

    /**
     * Bulk reset every ALERTING row back to MONITORING. Called by
     * [com.omama.stationalarm.service.LocationService] on cold start to
     * prevent ghost alarms when the process was killed mid-alarm: without
     * this, the first sync emission would treat any still-ALERTING DB row
     * as "newly alerting" and re-fire the alarm without user interaction.
     * Geofences for those stations were already removed at markAlerting()
     * time, so the user must enter the radius again for the alarm to fire.
     */
    suspend fun resetAllAlertingToMonitoring(): Int {
        val count = database.activeStationDao().resetAllAlertingToMonitoring()
        if (count > 0) Logger.log("ALERTING_RESET_BULK", extra = "count=$count")
        return count
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
            val result = GeofenceManager.addGeofencesForStation(
                context = appContext,
                stationId = updated.stationId,
                radiusLevel8M = (updated.radiusLevel8Km * 1000).toFloat(),
                radiusLevel7M = (updated.radiusLevel7Km * 1000).toFloat(),
                radiusLevel6M = (updated.radiusLevel6Km * 1000).toFloat(),
                radiusLevel5M = (updated.radiusLevel5Km * 1000).toFloat(),
                radiusLevel4M = (updated.radiusLevel4Km * 1000).toFloat(),
                radiusLevel3M = (updated.radiusLevel3Km * 1000).toFloat(),
                radiusLevel2M = (updated.radiusLevel2Km * 1000).toFloat(),
                radiusLevel1M = (updated.radiusLevel1Km * 1000).toFloat(),
                alertDistanceM = (updated.alertDistanceKm * 1000).toFloat()
            )
            result.onFailure { e ->
                _errorEvents.emit(e.message ?: "Failed to update alarm.")
                Logger.log("STATION_UPDATE_GEOFENCE_FAILED", stationId, e.message)
            }
            Logger.log("STATION_SETTINGS_UPDATED", stationId, "radius=$radius notify=$notify vibrate=$vibrate sound=$sound")
        }
    }
}