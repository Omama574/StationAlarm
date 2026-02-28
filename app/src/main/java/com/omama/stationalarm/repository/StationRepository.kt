package com.omama.stationalarm.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.geofence.GeofenceManager
import com.omama.stationalarm.util.Logger

object StationRepository {

    private const val PREFS_NAME = "station_alarm_prefs"
    private const val KEY_ACTIVE_STATIONS = "active_stations"

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val gson = Gson()

    private val activeStationsMap = mutableMapOf<String, ActiveStation>()
    private val _activeStationsLiveData = MutableLiveData<List<ActiveStation>>(emptyList())
    val activeStationsLiveData: LiveData<List<ActiveStation>> = _activeStationsLiveData

    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        StationData.initialize(appContext) // StationData now loads once
        loadActiveStations()
    }

    // Station master access – no Context needed now
    fun getStationById(id: String): Station? = StationData.getStationById(id)
    fun searchStations(query: String): List<Station> = StationData.searchStations(query)
    fun getAllStations(): List<Station> = StationData.getAllStations()

    fun addActiveStation(activeStation: ActiveStation) {
        synchronized(activeStationsMap) {
            activeStationsMap[activeStation.stationId] = activeStation
            saveToPrefs()
            updateLiveData()

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
        synchronized(activeStationsMap) {
            if (activeStationsMap.remove(stationId) != null) {
                saveToPrefs()
                updateLiveData()
                GeofenceManager.removeGeofencesForStation(appContext, stationId)
                Logger.log("STATION_REMOVED", stationId)
            }
        }
    }

    fun updateStationDistance(stationId: String, distanceKm: Double) {
        synchronized(activeStationsMap) {
            activeStationsMap[stationId]?.currentDistanceKm = distanceKm
            updateLiveData()
        }
    }

    fun getAllActiveStations(): List<ActiveStation> {
        synchronized(activeStationsMap) {
            return activeStationsMap.values.toList()
        }
    }

    fun isActive(stationId: String): Boolean {
        synchronized(activeStationsMap) {
            return activeStationsMap.containsKey(stationId)
        }
    }

    fun reRegisterAllGeofences() {
        val activeList = getAllActiveStations()
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

    private fun loadActiveStations() {
        try {
            val json = prefs.getString(KEY_ACTIVE_STATIONS, null)
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<List<ActiveStation>>() {}.type
                val list: List<ActiveStation> = gson.fromJson(json, type)
                synchronized(activeStationsMap) {
                    activeStationsMap.clear()
                    list.forEach { activeStationsMap[it.stationId] = it }
                }
            }
        } catch (e: Exception) {
            Logger.log("ERROR", extra = "Failed to load active stations: ${e.message}")
        } finally {
            updateLiveData()
        }
    }

    private fun saveToPrefs() {
        try {
            val list = activeStationsMap.values.toList()
            val json = gson.toJson(list)
            prefs.edit().putString(KEY_ACTIVE_STATIONS, json).apply()
        } catch (e: Exception) {
            Logger.log("ERROR", extra = "Failed to save active stations: ${e.message}")
        }
    }

    private fun updateLiveData() {
        val list = activeStationsMap.values.toList()
        _activeStationsLiveData.postValue(list)
    }
}