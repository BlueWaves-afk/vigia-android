package com.example.vigia.perception

data class HazardState(
    val hasHazard: Boolean,
    val type: String,        // e.g., "pothole", "brake", "none"
    val confidence: Float,   // 0.0 to 1.0 (was hazardScore)
    val sources: Set<String>,// e.g., {"vision", "imu"}
    val lastUpdated: Long = System.currentTimeMillis()
) {
    companion object {
        val Idle = HazardState(
            hasHazard = false,
            type = "none",
            confidence = 0f,
            sources = emptySet(),
            lastUpdated = 0L
        )
    }
}