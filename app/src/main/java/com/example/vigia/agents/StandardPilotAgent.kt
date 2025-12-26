package com.example.vigia.agents

import android.content.Context
import android.util.Log

class StandardPilotAgent(context: Context) {

    fun decideAction(observation: String): String {
        Log.i("StandardPilot", "Analyzing: $observation")

        val lower = observation.lowercase()

        return when {
            lower.contains("pothole") -> "Caution: Pothole detected ahead."
            lower.contains("speed") && lower.contains("limit") -> "Checking speed limit..."
            lower.contains("crash") -> "EMERGENCY: Crash detected. Slow down."
            else -> "System Normal. Monitoring..."
        }
    }
}