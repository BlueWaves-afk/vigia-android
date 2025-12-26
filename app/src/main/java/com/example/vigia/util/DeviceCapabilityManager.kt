package com.example.vigia.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.tensorflow.lite.nnapi.NnApiDelegate

enum class DeviceTier {
    HIGH_END, // Capable of running Phi-3.5 + Virtual Lidar
    LOW_END   // Lite Mode (Cloud Routing Only)
}

class DeviceCapabilityManager(private val context: Context) {

    /**
     * Determines if the device is capable of running high-end local AI.
     * Criteria:
     * 1. RAM >= 6GB (Required for Model + OS overhead)
     * 2. 64-bit Processor (Required for quantized libraries)
     * 3. Hardware Acceleration: GPU (GLES 3.1+) OR NPU (NNAPI)
     * 4. CPU Cores >= 8 (Proxy for modern SoC)
     */
    fun getDeviceTier(): DeviceTier {
        val totalRamGB = getTotalRAM()
        val cores = getCpuCoreCount()
        val is64Bit = is64BitProcessor()
        val hasGpuSupport = checkOpenGLSupport()
        val hasNpuSupport = checkNpuSupport()

        Log.i("VigiaHardware", "Specs -> RAM: ${totalRamGB}GB | Cores: $cores | 64-bit: $is64Bit | GLES 3.1+: $hasGpuSupport | NPU: $hasNpuSupport")

        // LOGIC: A device is High End if it has the Memory/CPU to load the model
        // AND at least one form of hardware acceleration (GPU or NPU) to run it fast.
        val isHighEnd = (totalRamGB >= 6) &&
                is64Bit &&
                (hasGpuSupport || hasNpuSupport) &&
                (cores >= 8)

        return if (isHighEnd) DeviceTier.HIGH_END else DeviceTier.LOW_END
    }

    private fun getTotalRAM(): Long {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        // Convert Total Bytes to GB
        return memInfo.totalMem / (1024 * 1024 * 1024)
    }

    private fun is64BitProcessor(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.any { it.contains("64") }
        } else {
            false
        }
    }

    private fun getCpuCoreCount(): Int {
        return Runtime.getRuntime().availableProcessors()
    }

    private fun checkOpenGLSupport(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val configInfo = activityManager.deviceConfigurationInfo
        // We need OpenGL ES 3.1 (0x00030001) or higher for the TFLite GPU Delegate
        return configInfo.reqGlEsVersion >= 0x00030001
    }

    /**
     * Attempts to initialize the NNAPI delegate to check for NPU presence.
     * This is a "try-and-fail" check because Android has no explicit "hasNPU()" API.
     */
    private fun checkNpuSupport(): Boolean {
        // NNAPI is only stable/useful for NPU acceleration on Android 10 (Q) +
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        return try {
            val options = NnApiDelegate.Options()
            // We create the delegate just to see if the driver accepts it.
            val delegate = NnApiDelegate(options)
            delegate.close()
            Log.d("VigiaHardware", "✅ NPU/NNAPI Driver detected.")
            true
        } catch (e: Exception) {
            // If this crashes or fails, the device likely lacks a proper NPU driver.
            Log.w("VigiaHardware", "⚠️ NPU/NNAPI check failed: ${e.message}")
            false
        }
    }
}