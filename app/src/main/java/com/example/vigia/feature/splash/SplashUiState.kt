package com.example.vigia.feature.splash

import com.example.vigia.feature.splash.model.ModelFile

data class SplashUiState(
    // startup
    val statusText: String = "Initializing...",
    val startupProgress: Int = 0,

    // download panel
    val showDownloadPanel: Boolean = false,
    val downloadButtonsEnabled: Boolean = true,
    val totalProgress: Int = 0,
    val currentFileProgress: Int = 0,
    val currentFileName: String = "",
    val downloadPercentText: String = "0%",

    val files: List<ModelFile> = emptyList()
)