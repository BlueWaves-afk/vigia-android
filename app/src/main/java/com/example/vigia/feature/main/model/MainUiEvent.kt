package com.example.vigia.feature.main.model

sealed class MainUiEvent {
    data class Toast(val message: String) : MainUiEvent()
    data class AddHazardMarker(val lat: Double, val lon: Double) : MainUiEvent()
    object OfflineAreaReady : MainUiEvent()
}