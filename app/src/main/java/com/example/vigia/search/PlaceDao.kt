package com.example.vigia.search

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaceDao {
    // This is the function SearchRepository is looking for
    @Query("SELECT * FROM places WHERE name LIKE '%' || :query || '%' OR type LIKE '%' || :query || '%' LIMIT 10")
    suspend fun searchPlaces(query: String): List<PlaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(places: List<PlaceEntity>)
}