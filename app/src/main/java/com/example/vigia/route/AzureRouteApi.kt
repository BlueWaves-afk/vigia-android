package com.example.vigia.route

import retrofit2.http.GET
import retrofit2.http.Query

interface AzureRouteApi {

    @GET("route/directions/json")
    suspend fun getRoutes(
        @Query("api-version") apiVersion: String = "1.0",
        @Query("subscription-key") key: String,
        @Query("query") query: String, // "lat,lon:lat,lon"

        // Alternatives / primary behavior
        @Query("maxAlternatives") maxAlternatives: Int = 2, // Ask for ~3 total
        @Query("routeType") routeType: String = "fastest",
        @Query("traffic") traffic: Boolean = true,

        // Optional "Google-Maps-like" controls (safe defaults)
        // Examples:
        // avoid="tolls" or "highways" or "tolls,highways" (Azure accepts comma-separated)
        @Query("avoid") avoid: String? = null,

        // travelMode: car, truck, taxi, bus, van, motorcycle, bicycle, pedestrian
        @Query("travelMode") travelMode: String? = "car",

        // Helps when traffic=true: "live" or "historic" or "none" depending on API behavior.
        // If Azure rejects this param for your plan/version, you can remove it safely.
        @Query("computeTravelTimeFor") computeTravelTimeFor: String? = "all",

        // For future turn-by-turn guidance (if you later parse instructions)
        @Query("instructionsType") instructionsType: String? = "tagged",
        @Query("language") language: String? = "en-US"
    ): RouteResponse
}