package com.example.vigia.perception

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ImuProcessor
 *
 * "The Inner Ear" of the system.
 * Listens to the Accelerometer to detect physical forces.
 */
class ImuProcessor(private val context: Context) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    // Callback to main PerceptionManager
    private var onEventCallback: ((ImuHazardEvent) -> Unit)? = null

    // State for gravity filtering (Low-pass filter)
    private val gravity = FloatArray(3)
    private val linear_acceleration = FloatArray(3)

    // Thresholds (in m/s^2)
    // 9.8 m/s^2 is 1G.
    private val THRESHOLD_BRAKE = 11.0f    // Hard stop (~1.1G)
    private val THRESHOLD_POTHOLE = 14.0f  // Sharp jolt (~1.4G)
    private val THRESHOLD_IMPACT = 25.0f   // Crash (~2.5G)

    // Debounce to prevent spamming the same pothole 100 times in 1 second
    private var lastEventTime = 0L
    private val DEBOUNCE_MS = 1500

    fun start(onEvent: (ImuHazardEvent) -> Unit) {
        this.onEventCallback = onEvent

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer != null) {
            // SENSOR_DELAY_GAME is a good balance (~20ms updates)
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            Log.d("ImuProcessor", "IMU listening...")
        } else {
            Log.e("ImuProcessor", "No Accelerometer found!")
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        onEventCallback = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {

            // 1. Isolate Gravity (Low-Pass Filter)
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

            // 2. Remove Gravity (High-Pass Filter)
            linear_acceleration[0] = event.values[0] - gravity[0]
            linear_acceleration[1] = event.values[1] - gravity[1]
            linear_acceleration[2] = event.values[2] - gravity[2]

            // 3. Calculate total magnitude of force (ignoring direction)
            val x = linear_acceleration[0]
            val y = linear_acceleration[1]
            val z = linear_acceleration[2]

            // Total G-Force (absolute intensity of movement)
            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

            // 4. Heuristics (Simple thresholds)
            detectEvents(magnitude, z)
        }
    }

    private fun detectEvents(magnitude: Float, zAxis: Float) {
        val now = System.currentTimeMillis()
        if (now - lastEventTime < DEBOUNCE_MS) return

        var type = "none"
        var severity = 0.0f

        when {
            // Priority 1: CRASH (Massive spike)
            magnitude > THRESHOLD_IMPACT -> {
                type = "impact"
                severity = 1.0f
            }

            // Priority 2: POTHOLE (Sharp vertical jolt, Z-axis dominant)
            // Note: This assumes phone is roughly flat or in a holder.
            // A more complex rotation matrix is needed for any orientation,
            // but this works for 80% of dashcam setups.
            magnitude > THRESHOLD_POTHOLE && abs(zAxis) > (magnitude * 0.6) -> {
                type = "pothole"
                severity = 0.6f
            }

            // Priority 3: HARSH BRAKING (Significant force, mostly horizontal)
            magnitude > THRESHOLD_BRAKE -> {
                type = "harsh_brake"
                severity = 0.8f
            }
        }

        if (type != "none") {
            Log.w("ImuProcessor", "Detected: $type (mag=$magnitude)")
            lastEventTime = now

            onEventCallback?.invoke(
                ImuHazardEvent(
                    type = type,
                    severity = severity // Using 'severity' to match PerceptionManager logic
                )
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}