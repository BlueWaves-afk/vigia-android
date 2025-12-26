package com.example.vigia.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HazardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hazards: List<HazardEntity>)

    // Get hazards inside the map viewport
    @Query("SELECT * FROM hazards WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun getHazardsInArea(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<HazardEntity>

    @Query("SELECT * FROM hazards")
    suspend fun getAllHazards(): List<HazardEntity>
}