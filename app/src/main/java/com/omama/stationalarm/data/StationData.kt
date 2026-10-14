package com.omama.stationalarm.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.omama.stationalarm.util.Logger
import java.io.InputStreamReader

object StationData {

    private var stations: List<Station>? = null
    @Volatile private var index: StationIndex? = null
    private const val STATIONS_FILE = "stations.json"

    fun initialize(context: Context) {
        if (stations != null) return
        try {
            val inputStream = context.assets.open(STATIONS_FILE)
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<List<Station>>() {}.type
            val parsed = Gson().fromJson<List<Station>?>(reader, type)
            reader.close()
            val loaded = parsed ?: emptyList()
            stations = loaded
            index = StationIndex(loaded)
            if (parsed.isNullOrEmpty()) {
                // Empty parse is silent corruption — surface as non-fatal so we know.
                Logger.error("STATION_DATA_EMPTY",
                    IllegalStateException("stations.json parsed to empty list"))
            }
        } catch (e: Exception) {
            // Previously this was e.printStackTrace() then silent emptyList — the
            // only signal was "No stations found" in search, indistinguishable from
            // a query miss. Now it's in the shareable log AND Crashlytics.
            Log.e("StationData", "Failed to load $STATIONS_FILE", e)
            Logger.error("STATION_DATA_LOAD_FAILED", e)
            stations = emptyList()
            index = StationIndex(emptyList())
        }
    }

    fun getStationById(id: String): Station? {
        return stations?.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    /** Best matches first (see [StationIndex]); empty until the JSON has loaded. */
    fun searchStations(query: String): List<Station> {
        if (query.isBlank()) return emptyList()
        return index?.search(query) ?: emptyList()
    }

    fun getAllStations(): List<Station> = stations ?: emptyList()
}