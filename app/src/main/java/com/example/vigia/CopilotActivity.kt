package com.example.vigia

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.vigia.agents.RoadPilotAgent
import com.example.vigia.agents.StandardPilotAgent
import com.example.vigia.perception.PerceptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class CopilotActivity : AppCompatActivity() {

    // 1. Managers
    private lateinit var perceptionManager: PerceptionManager

    // 2. Agents
    private lateinit var router: RouterAgent // Assumes RouterAgent is safe (Assets based)
    private var aiPilot: RoadPilotAgent? = null
    private var standardPilot: StandardPilotAgent? = null
    private var isLiteMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimal UI for "HUD Mode"
        val view = TextView(this).apply {
            text = "\n\n   VIGIA HUD ACTIVE\n\n   Systems Online.\n   Screen darkening to save battery..."
            textSize = 24f
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        setContentView(view)

        // ---------------------------------------------------------
        // A. SYNC: Receive Decision & VERIFY FILES
        // ---------------------------------------------------------
        isLiteMode = intent.getBooleanExtra("IS_LITE_MODE", false)

        // SAFETY CHECK: If app thinks it's AI mode, but files are missing, force Lite Mode.
        if (!isLiteMode) {
            val modelConfig = File(filesDir, "phi-3.5-mini-int4-cpu/genai_config.json")
            if (!modelConfig.exists()) {
                Log.e("Copilot", "⚠️ CRITICAL: AI Model files missing! Forcing Lite Mode.")
                isLiteMode = true
            }
        }

        // ---------------------------------------------------------
        // B. SYNC: Initialize the Brains
        // ---------------------------------------------------------
        try {
            router = RouterAgent(this)

            if (isLiteMode) {
                standardPilot = StandardPilotAgent(this)
                Log.d("Copilot", "✅ Standard Pilot (Rules) Active")
            } else {
                aiPilot = RoadPilotAgent(this)
                Log.d("Copilot", "✅ AI Pilot (Phi-3.5) Active")
            }
        } catch (e: Exception) {
            Log.e("Copilot", "Failed to init agents", e)
            // Last resort fallback
            standardPilot = StandardPilotAgent(this)
        }

        // ---------------------------------------------------------
        // C. SYNC: Start Perception Loop
        // ---------------------------------------------------------
        perceptionManager = PerceptionManager(applicationContext, lifecycleScope)
        perceptionManager.start()

        lifecycleScope.launch {
            perceptionManager.hazardState.collect { hazard ->
                if (hazard.hasHazard) {
                    processHazard(hazard.type)
                }
            }
        }
    }

    private fun processHazard(hazardType: String) {
        lifecycleScope.launch(Dispatchers.Default) {
            val actionText = "Hazard detected: $hazardType"

            // Route to the correct agent
            if (isLiteMode) {
                standardPilot?.decideAction(actionText)
            } else {
                aiPilot?.decideAction(actionText)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::router.isInitialized) router.close()
        if (::perceptionManager.isInitialized) perceptionManager.stop()

        // Close whichever pilot was running
        aiPilot?.close()
        // StandardPilot usually doesn't need closing, but good practice if it has resources
    }
}