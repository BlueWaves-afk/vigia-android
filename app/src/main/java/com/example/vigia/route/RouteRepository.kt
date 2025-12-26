package com.example.vigia.route

import android.location.Location
import com.example.vigia.search.HazardDao
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.example.vigia.BuildConfig
class RouteRepository(private val hazardDao: HazardDao) {

    private val azureApi: AzureRouteApi
    // TODO: In production, secure this key (e.g., using BuildConfig or a backend proxy)
    private val azureKey = BuildConfig.AZURE_MAPS_KEY

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://atlas.microsoft.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        azureApi = retrofit.create(AzureRouteApi::class.java)
    }

    /**
     * Calculates multiple routes and returns the Best FASTEST and Best SAFEST options.
     * Includes cost estimation for vehicle wear/damage based on hazard type.
     */
    suspend fun calculateRoutes(
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double
    ): Pair<VigiaRoute, VigiaRoute> { // Returns Pair(Fastest, Safest)

        val query = "$startLat,$startLon:$endLat,$endLon"

        try {
            // 1. CLOUD LAYER: Fetch multiple route alternatives (e.g., maxAlternatives=2 gives ~3 total)
            val response = azureApi.getRoutes(key = azureKey, query = query, maxAlternatives = 2)

            // 2. LOCAL LAYER: Fetch Digital Twin (All known hazards)
            val allHazards = hazardDao.getAllHazards()

            // 3. THE VIGIA ANALYSIS: Cross-reference routes against hazards & calculate costs
            val analyzedRoutes = response.routes.mapIndexed { index, route ->
                val points = route.legs.flatMap { it.points }

                var hazardHits = 0
                var totalDamageCost = 0.0

                // Scan this route for any intersections with hazards
                for (hazard in allHazards) {
                    // Check if route passes near this hazard
                    for (point in points) {
                        val results = FloatArray(1)
                        Location.distanceBetween(hazard.lat, hazard.lon, point.latitude, point.longitude, results)

                        // If route passes within 40m of a hazard (avg road width + GPS drift)
                        if (results[0] < 40) {
                            hazardHits++

                            // NEW: Assign estimated damage cost based on hazard type
                            // These values are estimates for tire/suspension damage or risk
                            totalDamageCost += when(hazard.type.lowercase()) {
                                "pothole" -> 60.0       // Tire/Rim damage est.
                                "ice" -> 250.0          // Accident risk est. (Weighted higher)
                                "construction" -> 15.0  // Wear/tear/debris
                                "accident" -> 100.0     // High delay/risk
                                else -> 10.0            // General caution
                            }

                            break // Count this specific hazard only once per route (even if multiple points are near it)
                        }
                    }
                }

                VigiaRoute(
                    id = "route_$index",
                    points = points,
                    durationSeconds = route.summary.travelTimeInSeconds,
                    distanceMeters = route.summary.lengthInMeters,
                    hazardCount = hazardHits,
                    damageCost = totalDamageCost, // Store the calculated cost
                    isFastest = (index == 0) // First result from Azure is standard "Fastest"
                )
            }

            // 4. SELECTION LOGIC
            if (analyzedRoutes.isEmpty()) return Pair(emptyRoute(), emptyRoute())

            // A. Identify Fastest: Usually index 0, but we double-check time just in case
            val fastest = analyzedRoutes.minByOrNull { it.durationSeconds } ?: analyzedRoutes[0]

            // B. Identify Safest: Lowest Cost. If tied on cost, pick the faster one.
            // If the fastest route has $0 cost, it is also the safest.
            val safest = analyzedRoutes.minWithOrNull(compareBy({ it.damageCost }, { it.durationSeconds })) ?: fastest

            // Return copies with the flags set correctly for UI logic
            return Pair(
                fastest.copy(isFastest = true),
                safest.copy(isSafest = true)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            // Return empty routes on failure to prevent crash
            return Pair(emptyRoute(), emptyRoute())
        }
    }

    // Helper for error states
    private fun emptyRoute() = VigiaRoute("error", emptyList(), 0, 0, 0, 0.0)
}