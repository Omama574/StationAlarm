package com.omama.stationalarm.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

object StationData {

    private var stations: List<Station>? = null
    private const val STATIONS_FILE = "stations.json"

    fun initialize(context: Context) {
        if (stations != null) return
        try {
            val inputStream = context.assets.open(STATIONS_FILE)
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<Station>>() {}.type
            stations = Gson().fromJson(reader, type)
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
            stations = emptyList()
        }
    }

    fun getStationById(id: String): Station? {
        return stations?.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun searchStations(query: String): List<Station> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase()
        return stations?.filter {
            it.id.lowercase().contains(lowerQuery) ||
                    it.name.lowercase().contains(lowerQuery)
        } ?: emptyList()
    }

    fun getAllStations(): List<Station> = stations ?: emptyList()
}