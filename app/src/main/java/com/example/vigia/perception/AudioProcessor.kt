package com.example.vigia.perception

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * AudioProcessor
 *
 * Implements a "Loudness Trigger" for the MVP.
 * Analyzes the microphone stream for sudden spikes in decibels (crashes, honks).
 */
class AudioProcessor(private val context: Context) {

    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null

    // Config
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Thresholds
    // 0dB is silence, 90dB is loud shouting/traffic.
    // We trigger on sudden spikes > 75dB (approximate for phone mic).
    private val noiseThresholdDb = 75.0

    @SuppressLint("MissingPermission") // Checked in MainActivity
    fun start(onEvent: (AudioHazardEvent) -> Unit) {
        if (audioJob != null) return // Already running

        audioJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("AudioProcessor", "Microphone init failed")
                    return@launch
                }

                audioRecord?.startRecording()
                Log.d("AudioProcessor", "Listening...")

                val buffer = ShortArray(bufferSize)

                while (isActive) {
                    val readResult = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (readResult > 0) {
                        val db = computeDb(buffer, readResult)

                        // Simple "System 1" Heuristic:
                        // If it's incredibly loud, assume it's a hazard (Horn/Crash).
                        if (db > noiseThresholdDb) {
                            Log.w("AudioProcessor", "Loud Noise Detected: ${db.toInt()} dB")

                            // For MVP, we label mostly as "horn" or "impact" based on loudness
                            val type = if (db > 85) "impact" else "horn"
                            val confidence = ((db - noiseThresholdDb) / 20.0).coerceIn(0.5, 1.0).toFloat()

                            onEvent(
                                AudioHazardEvent(
                                    type = type,
                                    confidence = confidence
                                )
                            )

                            // Debounce: Wait 1s to avoid spamming alerts for the same honk
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    // Loop delay not needed as .read() is blocking/timed by sample rate
                }

            } catch (e: Exception) {
                Log.e("AudioProcessor", "Error in audio loop", e)
            } finally {
                release()
            }
        }
    }

    fun stop() {
        audioJob?.cancel()
        release()
    }

    private fun release() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore stop errors
        }
        audioRecord = null
        audioJob = null
    }

    /**
     * Calculate Decibels (dB) from PCM 16-bit buffer.
     * Uses Root Mean Square (RMS) amplitude.
     */
    private fun computeDb(buffer: ShortArray, readSize: Int): Double {
        var sum = 0.0
        for (i in 0 until readSize) {
            val sample = buffer[i] / 32768.0 // Normalize 16-bit to -1.0..1.0
            sum += sample * sample
        }
        val rms = sqrt(sum / readSize)

        // Avoid log(0)
        return if (rms > 0) {
            20 * log10(rms) + 90 // +90 is an arbitrary calibration offset for mobile mics
        } else {
            0.0
        }
    }
}