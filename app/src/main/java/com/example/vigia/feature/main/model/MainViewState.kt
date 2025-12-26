package com.example.vigia.feature.main.model

import com.example.vigia.perception.HazardState
import com.example.vigia.route.VigiaRoute
import com.example.vigia.search.PlaceEntity

data class MainViewState(
    val uiState: MainUiState = MainUiState.IDLE,

    val isLiteMode: Boolean = false,
    val isAiReady: Boolean = false,

    val agentLog: String = "Initializing AI...",
    val isListening: Boolean = false,

    val hazardState: HazardState = HazardState(
        hasHazard = false,
        type = "none",
        confidence = 0.0f,
        sources = emptySet(),
        lastUpdated = 0L
    ),

    val searchResults: List<PlaceEntity> = emptyList(),

    val destinationName: String = "",
    val fastestTimeText: String = "...",
    val fastestCostText: String = "",
    val safestTimeText: String = "...",
    val safestCostText: String = "",

    val fastestRoute: VigiaRoute? = null,
    val safestRoute: VigiaRoute? = null,
    val selectedRoute: VigiaRoute? = null,
    val isFastestSelected: Boolean = true
)