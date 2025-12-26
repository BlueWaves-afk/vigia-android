package com.example.vigia.feature.splash.model

data class ModelFile(
    val fileName: String,
    val status: FileStatus = FileStatus.PENDING
)