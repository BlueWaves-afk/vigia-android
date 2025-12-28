package com.example.vigia.route

import android.location.Location
import com.example.vigia.BuildConfig
import com.example.vigia.search.HazardDao
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class RouteRepository(private val hazardDao: HazardDao) {

    private val azureApi: AzureRouteApi
    private val azureKey = BuildConfig.AZURE_MAPS_KEY

    // Tuning knobs (safe defaults)
    private val HAZARD_MATCH_RADIUS_M = 40f

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://atlas.microsoft.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        azureApi = retrofit.create(AzureRouteApi::class.java)
    }

    /**
     * Backwards-compatible API (your existing call sites keep working).
     */
    suspend fun calculateRoutes(
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double
    ): Pair<VigiaRoute, VigiaRoute> {
        return calculateRoutes(
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
            settings = RouteSettings()
        )
    }

    /**
     * New: route calculation with explicit settings (used by navigation reroute if you want).
     * Still returns Pair(Fastest, Safest) based on:
     * - Fastest = min travel time
     * - Safest = min damageCost (tie-breaker: min travel time)
     */
    suspend fun calculateRoutes(
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double,
        settings: RouteSettings
    ): Pair<VigiaRoute, VigiaRoute> {

        val query = "$startLat,$startLon:$endLat,$endLon"

        return try {
            // 1) CLOUD: fetch alternatives from Azure
            val response = azureApi.getRoutes(
                key = azureKey,
                query = query,
                maxAlternatives = settings.maxAlternatives,
                routeType = settings.routeType,
                traffic = settings.traffic
                // If you later extend AzureRouteApi with "avoid" or "travelMode", you can wire it here too.
            )

            // 2) LOCAL: known hazards
            val allHazards = hazardDao.getAllHazards()

            // 3) ANALYZE: route-vs-hazard intersection + damage cost
            val analyzedRoutes = response.routes.mapIndexed { index, route ->
                val points = route.legs.flatMap { it.points }

                var hazardHits = 0
                var totalDamageCost = 0.0

                for (hazard in allHazards) {
                    for (point in points) {
                        val results = FloatArray(1)
                        Location.distanceBetween(
                            hazard.lat, hazard.lon,
                            point.latitude, point.longitude,
                            results
                        )

                        if (results[0] < HAZARD_MATCH_RADIUS_M) {
                            hazardHits++

                            totalDamageCost += when (hazard.type.lowercase()) {
                                "pothole" -> 60.0
                                "ice" -> 250.0
                                "construction" -> 15.0
                                "accident" -> 100.0
                                else -> 10.0
                            }

                            break // count each hazard only once per route
                        }
                    }
                }

                VigiaRoute(
                    id = "route_$index",
                    points = points,
                    durationSeconds = route.summary.travelTimeInSeconds,
                    distanceMeters = route.summary.lengthInMeters,
                    hazardCount = hazardHits,
                    damageCost = totalDamageCost,
                    isFastest = (index == 0) // Azure often returns standard best first, but we also compute min-time below
                )
            }

            if (analyzedRoutes.isEmpty()) return Pair(emptyRoute(), emptyRoute())

            // Fastest = min travel time
            val fastest = analyzedRoutes.minByOrNull { it.durationSeconds } ?: analyzedRoutes[0]

            // Safest = min damage cost; tie-breaker by time
            val safest = analyzedRoutes.minWithOrNull(compareBy({ it.damageCost }, { it.durationSeconds })) ?: fastest

            Pair(
                fastest.copy(isFastest = true),
                safest.copy(isSafest = true)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(emptyRoute(), emptyRoute())
        }
    }

    data class RouteSettings(
        val routeType: String = "fastest",  // Azure supports fastest / shortest / etc.
        val traffic: Boolean = true,
        val maxAlternatives: Int = 2
        // Future: val avoid: String? = null (tolls, ferries, highways), travelMode, etc.
    )

    private fun emptyRoute() = VigiaRoute("error", emptyList(), 0, 0, 0, 0.0)
}