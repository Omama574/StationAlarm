package com.omama.stationalarm.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.Exception

object StationData {
    private const val TAG = "StationData"
    private const val ASSET_FILE = "stations.json"

    // cached list
    @Volatile
    private var cachedStations: List<Station>? = null

    private val gson = Gson()

    // Load once, thread-safe
    private fun loadIfNeeded(context: Context) {
        if (cachedStations != null) return
        synchronized(this) {
            if (cachedStations != null) return
            try {
                val input = context.assets.open(ASSET_FILE)
                val reader = BufferedReader(InputStreamReader(input))
                val json = reader.use { it.readText() }
                val listType = object : TypeToken<List<Station>>() {}.type
                cachedStations = gson.fromJson(json, listType) ?: emptyList()
            } catch (e: Exception) {
                Log.d(TAG, "Failed to load $ASSET_FILE: ${e.message}")
                cachedStations = emptyList()
            }
        }
    }

    fun getAllStations(context: Context): List<Station> {
        loadIfNeeded(context)
        return cachedStations ?: emptyList()
    }

    fun getStationById(context: Context, id: String): Station? {
        if (id.isBlank()) return null
        loadIfNeeded(context)
        return cachedStations?.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    fun searchStations(context: Context, query: String): List<Station> {
        val q = query.trim()
        if (q.isEmpty()) return getAllStations(context)
        loadIfNeeded(context)
        return cachedStations
            ?.filter {
                it.name.contains(q, ignoreCase = true) || it.id.equals(q, ignoreCase = true)
            } ?: emptyList()
    }

    // For debugging while testing
    fun clearCacheForDebug() {
        cachedStations = null
    }
}
