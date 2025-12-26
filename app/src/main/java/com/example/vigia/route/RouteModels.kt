package com.example.vigia.route

import com.google.gson.annotations.SerializedName

// Azure API Response Wrappers
data class RouteResponse(val routes: List<Route>)
data class Route(val summary: RouteSummary, val legs: List<RouteLeg>)
data class RouteSummary(
    val lengthInMeters: Int,
    val travelTimeInSeconds: Int,
    val trafficDelayInSeconds: Int
)
data class RouteLeg(val points: List<RoutePoint>)
data class RoutePoint(val latitude: Double, val longitude: Double)

// Our App's Smart Route Object
data class VigiaRoute(
    val id: String,
    val points: List<RoutePoint>,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val hazardCount: Int,
    val damageCost: Double, // NEW: Estimated vehicle damage in $$$
    val isSafest: Boolean = false,
    val isFastest: Boolean = false
)