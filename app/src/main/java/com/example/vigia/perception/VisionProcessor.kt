package com.example.vigia.perception

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

// Note: If you already added this data class in PerceptionData.kt, delete it here.
// If not, keep it. It matches what PerceptionManager expects.
/*
data class VisionHazardFeatures(
    val vehicleAheadClose: Boolean = false,
    val pedestrianInPath: Boolean = false,
    val redLightInFront: Boolean = false,
    val potholeAhead: Boolean = false,
    val speedKmh: Float = 0f,
    val timestampMs: Long = System.currentTimeMillis()
)
*/

/**
 * VisionProcessor (Demo / Simulator Edition)
 *
 * Currently: Simulates detection events to test the "Brain" and UI.
 * Future: Will wrap CameraX + YOLOv8 TFLite model.
 */
class VisionProcessor(private val context: Context) {

    private var detectionJob: Job? = null

    // Demo scenarios to cycle through
    private val scenarios = listOf(
        "safe", "safe", "safe",       // Mostly safe driving
        "pothole",                    // Sudden pothole
        "safe", "safe",
        "pedestrian",                 // Dangerous pedestrian crossing
        "safe",
        "red_light"                   // Running a light
    )

    fun start(onFeatures: (VisionHazardFeatures) -> Unit) {
        if (detectionJob != null) return

        Log.d("VisionProcessor", "Starting Camera Simulation...")

        detectionJob = CoroutineScope(Dispatchers.Default).launch {
            var index = 0

            while (isActive) {
                // 1. Simulate Frame Processing Rate (e.g., 10 FPS = 100ms)
                // We keep this loop fast, but only change scenarios slowly
                delay(2000) // Change scenario every 2 seconds for demo purposes

                val scenario = scenarios[index % scenarios.size]
                index++

                // 2. Generate Fake Features based on Scenario
                val features = when (scenario) {
                    "pothole" -> VisionHazardFeatures(
                        potholeAhead = true,
                        speedKmh = 60f // Fast speed + pothole = Hazard
                    )
                    "pedestrian" -> VisionHazardFeatures(
                        pedestrianInPath = true,
                        speedKmh = 30f // Even low speed = Hazard
                    )
                    "red_light" -> VisionHazardFeatures(
                        redLightInFront = true,
                        speedKmh = 50f // Running light = Hazard
                    )
                    else -> VisionHazardFeatures(
                        speedKmh = 60f // Just cruising
                    )
                }

                // 3. Log and Emit
                if (scenario != "safe") {
                    Log.i("VisionProcessor", "Simulated Detection: $scenario")
                    onFeatures(features)
                }
            }
        }
    }

    fun stop() {
        Log.d("VisionProcessor", "Stopping Camera.")
        detectionJob?.cancel()
        detectionJob = null
    }
}