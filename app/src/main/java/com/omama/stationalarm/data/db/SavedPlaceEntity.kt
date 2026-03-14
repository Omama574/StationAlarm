package com.omama.stationalarm.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.omama.stationalarm.data.SavedPlace

@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val notify: Boolean,
    val vibrate: Boolean,
    val sound: Boolean,
    val notes: String?,
    val createdAt: Long
)

fun SavedPlaceEntity.toDomainModel(): SavedPlace = SavedPlace(
    id = id,
    name = name,
    lat = lat,
    lon = lon,
    radiusKm = radiusKm,
    notify = notify,
    vibrate = vibrate,
    sound = sound,
    notes = notes,
    createdAt = createdAt
)

fun SavedPlace.toEntity(): SavedPlaceEntity = SavedPlaceEntity(
    id = id,
    name = name,
    lat = lat,
    lon = lon,
    radiusKm = radiusKm,
    notify = notify,
    vibrate = vibrate,
    sound = sound,
    notes = notes,
    createdAt = createdAt
)
