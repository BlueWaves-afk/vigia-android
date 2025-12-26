package com.example.vigia.feature.splash

sealed interface SplashEvent {
    data class NavigateToLanding(val liteMode: Boolean) : SplashEvent
    data class ToastMessage(val message: String) : SplashEvent
}