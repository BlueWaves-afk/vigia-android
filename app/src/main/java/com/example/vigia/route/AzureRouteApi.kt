package com.example.vigia.route

import retrofit2.http.GET
import retrofit2.http.Query

interface AzureRouteApi {
    @GET("route/directions/json")
    suspend fun getRoutes(
        @Query("api-version") apiVersion: String = "1.0",
        @Query("subscription-key") key: String,
        @Query("query") query: String, // "lat,lon:lat,lon"
        @Query("maxAlternatives") maxAlternatives: Int = 2, // Ask for 3 total
        @Query("routeType") routeType: String = "fastest",
        @Query("traffic") traffic: Boolean = true
    ): RouteResponse
}