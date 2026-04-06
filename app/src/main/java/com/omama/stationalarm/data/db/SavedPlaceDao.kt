package com.omama.stationalarm.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaceDao {

    @Query("SELECT * FROM saved_places ORDER BY createdAt DESC")
    fun getAllSavedPlaces(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places ORDER BY createdAt DESC")
    suspend fun getAllSavedPlacesList(): List<SavedPlaceEntity>

    @Query("SELECT * FROM saved_places WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SavedPlaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(place: SavedPlaceEntity)

    @Query("DELETE FROM saved_places WHERE id = :id")
    suspend fun delete(id: String)
}
