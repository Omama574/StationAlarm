package com.omama.stationalarm.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.data.StationData
import com.omama.stationalarm.util.Logger

object StationRepository {

    private const val PREFS_NAME = "station_alarm_prefs"
    private const val KEY_ACTIVE_STATIONS = "active_stations"

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val gson = Gson()
    private val activeStations = mutableMapOf<String, ActiveStation>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    // --------------------------------
    // Station master access
    // --------------------------------

    fun getStationById(id: String): Station? {
        return StationData.getStationById(appContext, id)
    }

    fun searchStations(query: String): List<Station> {
        return StationData.searchStations(appContext, query)
    }

    fun getAllStations(): List<Station> {
        return StationData.getAllStations(appContext)
    }

    // --------------------------------
    // Active station management
    // --------------------------------

    fun addStation(activeStation: ActiveStation) {
        activeStations[activeStation.stationId] = activeStation
        saveToPrefs()

        // Register geofences
        try {
            com.omama.stationalarm.geofence.GeofenceManager
                .addGeofencesForStation(
                    appContext,
                    activeStation.stationId,
                    activeStation.alertDistanceKm
                )
        } catch (e: Exception) {
            Logger.log("ERROR", extra = "Failed to register geofence: ${e.message}")
        }
    }

    fun removeStation(stationId: String) {
        if (activeStations.containsKey(stationId)) {
            activeStations.remove(stationId)
            saveToPrefs()

            // Remove geofences
            try {
                com.omama.stationalarm.geofence.GeofenceManager
                    .removeGeofencesForStation(appContext, stationId)
            } catch (e: Exception) {
                Logger.log("ERROR", extra = "Failed to remove geofence: ${e.message}")
            }
        }
    }

    fun getAllActiveStations(): List<ActiveStation> {
        return activeStations.values.toList()
    }

    fun isActive(stationId: String): Boolean {
        return activeStations.containsKey(stationId)
    }

    // --------------------------------
    // Persistence
    // --------------------------------

    private fun loadFromPrefs() {
        try {
            val jsonString = prefs.getString(KEY_ACTIVE_STATIONS, null)
            if (!jsonString.isNullOrEmpty()) {
                val type = object : TypeToken<List<ActiveStation>>() {}.type
                val stations: List<ActiveStation> =
                    gson.fromJson(jsonString, type)

                activeStations.clear()
                stations.forEach {
                    activeStations[it.stationId] = it
                }
            }
        } catch (e: Exception) {
            Logger.log("ERROR", extra = "Failed to load active stations: ${e.message}")
            activeStations.clear()
        }
    }

    private fun saveToPrefs() {
        try {
            val jsonString = gson.toJson(activeStations.values.toList())
            prefs.edit().putString(KEY_ACTIVE_STATIONS, jsonString).apply()
        } catch (e: Exception) {
            Logger.log("ERROR", extra = "Failed to save active stations: ${e.message}")
        }
    }
}