package com.example.vigia.feature.splash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vigia.RouterAgent
import com.example.vigia.util.DeviceCapabilityManager
import com.example.vigia.util.DeviceTier
import com.example.vigia.feature.splash.data.ModelDownloader
import com.example.vigia.feature.splash.data.ModelFileValidator
import com.example.vigia.feature.splash.data.ModelManifest
import com.example.vigia.feature.splash.model.FileStatus
import com.example.vigia.feature.splash.model.ModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class SplashViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(
        SplashUiState(files = ModelManifest.REQUIRED_FILES.map { ModelFile(it) })
    )
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SplashEvent>()
    val events = _events.asSharedFlow()

    private val validator = ModelFileValidator()

    // Extended timeout for large file
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloader = ModelDownloader(okHttp)

    fun start() {
        viewModelScope.launch {
            // A) Warm router (best effort)
            withContext(Dispatchers.IO) {
                try { RouterAgent(getApplication()) } catch (_: Exception) {}
            }

            // B) Device tier
            setStartupStatus("Analyzing Neural Engine...", 40)
            val tier = withContext(Dispatchers.IO) {
                delay(600)
                DeviceCapabilityManager(getApplication()).getDeviceTier()
            }

            if (tier == DeviceTier.LOW_END) {
                setStartupStatus("Standard Hardware Detected. Optimizing...", 90)
                delay(1000)
                _events.emit(SplashEvent.NavigateToLanding(liteMode = true))
                return@launch
            }

            setStartupStatus("Verifying AI Models...", 60)
            delay(500)

            // C) Validate existing
            val dir = File(getApplication<Application>().filesDir, ModelManifest.LOCAL_DIR_NAME)
            val result = withContext(Dispatchers.IO) { validator.validateOrCleanup(dir, ModelManifest.REQUIRED_FILES) }

            val updatedList = ModelManifest.REQUIRED_FILES.mapIndexed { idx, name ->
                ModelFile(name, if (result.perFileValid[idx]) FileStatus.COMPLETED else FileStatus.PENDING)
            }
            _uiState.value = _uiState.value.copy(files = updatedList)

            if (result.allValid) {
                setStartupStatus("AI Engine Ready.", 100)
                delay(500)
                _events.emit(SplashEvent.NavigateToLanding(liteMode = false))
            } else {
                _uiState.value = _uiState.value.copy(showDownloadPanel = true)
            }
        }
    }

    fun onSkipDownload() {
        viewModelScope.launch {
            _events.emit(SplashEvent.NavigateToLanding(liteMode = true))
        }
    }

    fun onStartDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(downloadButtonsEnabled = false)

            val dir = File(getApplication<Application>().filesDir, ModelManifest.LOCAL_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()

            var globalSuccess = true
            val files = _uiState.value.files.toMutableList()

            for ((index, item) in files.withIndex()) {
                // Skip if already completed and exists
                val existing = File(dir, item.fileName)
                if (item.status == FileStatus.COMPLETED && existing.exists() && existing.length() > 0) continue

                // mark downloading
                files[index] = item.copy(status = FileStatus.DOWNLOADING)
                emitFiles(files)

                updateDownloadUi(
                    currentFileName = "Downloading ${item.fileName}...",
                    currentFileProgress = 0,
                    percentText = "0%"
                )

                val success = downloader.downloadAtomic(
                    baseUrl = ModelManifest.AZURE_BASE_URL,
                    fileName = item.fileName,
                    dir = dir
                ) { bytesRead, totalBytes ->
                    if (totalBytes > 0) {
                        val p = ((bytesRead * 100) / totalBytes).toInt()
                        // throttle lightly
                        if (p % 2 == 0) {
                            updateDownloadUi(
                                currentFileName = null,
                                currentFileProgress = p,
                                percentText = "$p%"
                            )
                        }
                    }
                }

                if (success) {
                    files[index] = item.copy(status = FileStatus.COMPLETED)
                    emitFiles(files)
                } else {
                    files[index] = item.copy(status = FileStatus.ERROR)
                    emitFiles(files)
                    globalSuccess = false
                    break
                }

                val totalProgress = ((index + 1) * 100) / ModelManifest.REQUIRED_FILES.size
                _uiState.value = _uiState.value.copy(totalProgress = totalProgress)
            }

            withContext(Dispatchers.Main) {
                if (globalSuccess) {
                    _uiState.value = _uiState.value.copy(currentFileName = "Finalizing...")
                    delay(800)
                    _events.emit(SplashEvent.NavigateToLanding(liteMode = false))
                } else {
                    _uiState.value = _uiState.value.copy(downloadButtonsEnabled = true, currentFileName = "Error occurred.")
                    _events.emit(SplashEvent.ToastMessage("Download Failed. Please check internet."))
                }
            }
        }
    }

    private fun setStartupStatus(text: String, progress: Int) {
        _uiState.value = _uiState.value.copy(statusText = text, startupProgress = progress)
    }

    private fun emitFiles(newFiles: List<ModelFile>) {
        _uiState.value = _uiState.value.copy(files = newFiles)
    }

    private fun updateDownloadUi(
        currentFileName: String?,
        currentFileProgress: Int?,
        percentText: String?
    ) {
        val s = _uiState.value
        _uiState.value = s.copy(
            currentFileName = currentFileName ?: s.currentFileName,
            currentFileProgress = currentFileProgress ?: s.currentFileProgress,
            downloadPercentText = percentText ?: s.downloadPercentText
        )
    }
}