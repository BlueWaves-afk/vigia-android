package com.example.vigia.search

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val type: String, // e.g., "hospital", "police"
    val isCritical: Boolean = false // True means "Show this even when offline"
)