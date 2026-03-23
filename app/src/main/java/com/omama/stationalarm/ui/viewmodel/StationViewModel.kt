package com.omama.stationalarm.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.omama.stationalarm.StationAlarmApplication
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.data.Station
import com.omama.stationalarm.repository.StationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StationViewModel(application: Application) : AndroidViewModel(application) {

    // Optional cast to your custom application if needed
    private val app = application as StationAlarmApplication

    val activeStations: LiveData<List<ActiveStation>> = kotlinx.coroutines.flow.combine(
        StationRepository.activeStationsFlow,
        StationRepository.distancesFlow
    ) { stations, distances ->
        stations.map { it.copy(currentDistanceKm = distances[it.stationId]) }
    }.asLiveData(Dispatchers.IO)

    fun searchStations(query: String): List<Station> {
        return StationRepository.searchStations(query)
    }

    fun addActiveStation(activeStation: ActiveStation, customStation: Station? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            StationRepository.addActiveStation(activeStation, customStation)
        }
    }

    fun removeActiveStation(stationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            StationRepository.removeActiveStation(stationId)
        }
    }

    fun updateActiveStationSettings(
        stationId: String,
        radius: Double,
        notify: Boolean,
        vibrate: Boolean,
        sound: Boolean,
        reminder: String?,
        sendReminder: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            StationRepository.updateActiveStationSettings(stationId, radius, notify, vibrate, sound, reminder, sendReminder)
        }
    }

    fun getStationById(id: String): Station? = StationRepository.getStationByIdSync(id)
}