package com.example.vigia.perception

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

class PerceptionManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    // The single source of truth for "How safe is the car right now?"
    private val _hazardState = MutableStateFlow(HazardState.Idle)
    val hazardState: StateFlow<HazardState> get() = _hazardState

    // Sub-processors (Senses)
    private val visionProcessor = VisionProcessor(context)
    private val imuProcessor = ImuProcessor(context)
    private val audioProcessor = AudioProcessor(context)

    private var running = false

    // Auto-reset job to clear hazards after they pass
    private var hazardClearJob: Job? = null

    // Thresholds
    private val hazardThreshold = 0.4f // If confidence > 40%, trigger alert

    fun start() {
        if (running) return
        running = true

        // 1. Eyes (Camera)
        visionProcessor.start { features ->
            val (type, conf) = analyzeVision(features)
            if (conf > 0) reportHazard(type, conf, "vision")
        }

        // 2. Balance (Accelerometer/Gyro)
        imuProcessor.start { event ->
            val (type, conf) = analyzeImu(event)
            if (conf > 0) reportHazard(type, conf, "imu")
        }

        // 3. Ears (Microphone)
        audioProcessor.start { event ->
            val (type, conf) = analyzeAudio(event)
            if (conf > 0) reportHazard(type, conf, "audio")
        }
    }

    fun stop() {
        if (!running) return
        running = false

        visionProcessor.stop()
        imuProcessor.stop()
        audioProcessor.stop()
        hazardClearJob?.cancel()
        _hazardState.value = HazardState.Idle
    }

    // --- Analysis Logic (Returns Type + Confidence 0.0-1.0) ---

    private fun analyzeVision(f: VisionHazardFeatures): Pair<String, Float> {
        return when {
            f.vehicleAheadClose -> "collision_warning" to 0.9f
            f.pedestrianInPath -> "pedestrian" to 0.95f
            f.potholeAhead && f.speedKmh > 30f -> "pothole" to 0.7f
            f.redLightInFront && f.speedKmh > 40f -> "red_light_violation" to 0.8f
            else -> "none" to 0.0f
        }
    }

    private fun analyzeImu(e: ImuHazardEvent): Pair<String, Float> {
        return when (e.type) {
            "harsh_brake" -> "harsh_brake" to 0.8f
            "impact"      -> "crash" to 1.0f
            "pothole"     -> "rough_road" to 0.6f
            else          -> "none" to 0.0f
        }
    }

    private fun analyzeAudio(e: AudioHazardEvent): Pair<String, Float> {
        return when (e.type) {
            "scream"       -> "distress" to (0.9f * e.confidence)
            "tyre_screech" -> "skid" to (0.8f * e.confidence)
            "horn"         -> "horn" to (0.5f * e.confidence)
            else           -> "none" to 0.0f
        }
    }

    // --- State Update Logic ---

    private fun reportHazard(type: String, confidence: Float, source: String) {
        if (!running) return

        // Cancel pending reset since we have a fresh hazard
        hazardClearJob?.cancel()

        scope.launch(Dispatchers.Default) {
            val prev = _hazardState.value

            // Simple Fusion: If new confidence is higher, take it.
            // If it's a different source adding to existing hazard, boost confidence slightly.
            var newConf = confidence
            if (prev.hasHazard && prev.type == type) {
                newConf = min(1.0f, prev.confidence + 0.1f) // Boost existing
            }

            // Only update if it crosses threshold
            val isHazard = newConf >= hazardThreshold

            _hazardState.value = HazardState(
                hasHazard = isHazard,
                type = type,
                confidence = newConf,
                sources = prev.sources + source,
                lastUpdated = System.currentTimeMillis()
            )

            // Auto-reset logic: If no new hazards come in for 3 seconds, clear the state.
            // This mimics the "Reflex" relaxing after the danger passes.
            hazardClearJob = launch {
                delay(3000)
                _hazardState.value = HazardState.Idle
            }
        }
    }
}