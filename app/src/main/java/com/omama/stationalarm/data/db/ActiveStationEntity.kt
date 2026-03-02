package com.omama.stationalarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.omama.stationalarm.data.ActiveStation

@Entity(tableName = "active_stations")
data class ActiveStationEntity(
    @PrimaryKey val stationId: String,
    val alertDistanceKm: Double,
    val notify: Boolean,
    val vibrate: Boolean,
    val sound: Boolean,
    val customReminder: String?,
    val sendReminder: Boolean,
    val status: String = "MONITORING"
)

fun ActiveStationEntity.toDomainModel(): ActiveStation {
    return ActiveStation(
        stationId = stationId,
        alertDistanceKm = alertDistanceKm,
        notify = notify,
        vibrate = vibrate,
        sound = sound,
        currentDistanceKm = null,
        customReminder = customReminder,
        sendReminder = sendReminder,
        status = status
    )
}

fun ActiveStation.toEntity(): ActiveStationEntity {
    return ActiveStationEntity(
        stationId = stationId,
        alertDistanceKm = alertDistanceKm,
        notify = notify,
        vibrate = vibrate,
        sound = sound,
        customReminder = customReminder,
        sendReminder = sendReminder,
        status = status
    )
}
