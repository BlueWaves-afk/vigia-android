package com.example.vigia.perception

data class VisionHazardFeatures(
    val vehicleAheadClose: Boolean = false,
    val pedestrianInPath: Boolean = false,
    val redLightInFront: Boolean = false,
    val potholeAhead: Boolean = false,
    val speedKmh: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ImuHazardEvent(
    val type: String,
    val severity: Float
)

data class AudioHazardEvent(
    val type: String,
    val confidence: Float
)