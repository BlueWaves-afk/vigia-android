package com.example.vigia.search

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class OfflineManager(
    private val context: Context,
    private val searchRepo: SearchRepository, // Uses the repo that now supports automatic caching
    private val placeDao: PlaceDao,
    private val hazardDao: HazardDao
) {

    // Simulating a Microsoft Fabric API call (Replace with real Retrofit call later)
    private suspend fun fetchHazardsFromFabric(lat: Double, lon: Double): List<HazardEntity> {
        delay(1500) // Simulate network delay

        // Mock Data: Generating hazards relative to the user's download location
        return listOf(
            HazardEntity(type = "pothole", lat = lat + 0.002, lon = lon + 0.002, severity = "medium", timestamp = System.currentTimeMillis()),
            HazardEntity(type = "ice", lat = lat - 0.002, lon = lon - 0.001, severity = "high", timestamp = System.currentTimeMillis()),
            HazardEntity(type = "accident", lat = lat + 0.001, lon = lon - 0.003, severity = "high", timestamp = System.currentTimeMillis())
        )
    }

    suspend fun downloadArea(lat: Double, lon: Double, onProgress: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                // 1. Download Critical Infrastructure (Hospitals, Police, Gas)
                // We iterate through critical categories. The searchRepo.search() method
                // now has built-in logic to SAVE these results to the local Room DB automatically.
                val criticalQueries = listOf("Hospital", "Police", "Gas Station", "Shelter")

                criticalQueries.forEachIndexed { index, query ->
                    onProgress("Downloading $query locations... (${index + 1}/${criticalQueries.size})")
                    val results = searchRepo.search(query, lat, lon)
                    Log.d("VigiaOffline", "Downloaded & Cached ${results.size} items for $query")
                }

                // 2. Download Hazards from Fabric
                onProgress("Syncing Hazard Map...")
                val hazards = fetchHazardsFromFabric(lat, lon)

                // CRITICAL FIX: Validate data before saving to prevent GL Map Crashes
                val validHazards = hazards.filter { !it.lat.isNaN() && !it.lon.isNaN() }

                if (validHazards.isNotEmpty()) {
                    hazardDao.insertAll(validHazards)
                    Log.d("VigiaOffline", "Saved ${validHazards.size} hazards to local DB")
                } else {
                    Log.w("VigiaOffline", "No valid hazards returned from source.")
                }

                // 3. Final Success State
                onProgress("Offline Area Ready!")

            } catch (e: Exception) {
                Log.e("VigiaOffline", "Download failed: ${e.message}")
                onProgress("Download Error!")
            }
        }
    }
}