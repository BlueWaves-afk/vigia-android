package com.example.vigia.search

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hazards")
data class HazardEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,       // "pothole", "accident", "ice"
    val lat: Double,
    val lon: Double,
    val severity: String,   // "low", "high"
    val timestamp: Long
)