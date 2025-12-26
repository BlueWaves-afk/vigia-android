package com.example.vigia.feature.main.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vigia.AzureIoTManager
import com.example.vigia.CloudAgentClient
import com.example.vigia.RouterAgent
import com.example.vigia.agents.RoadPilotAgent
import com.example.vigia.agents.StandardPilotAgent
import com.example.vigia.feature.main.model.MainUiEvent
import com.example.vigia.feature.main.model.MainUiState
import com.example.vigia.feature.main.model.MainViewState
import com.example.vigia.perception.PerceptionManager
import com.example.vigia.route.RouteRepository
import com.example.vigia.search.OfflineManager
import com.example.vigia.search.PlaceEntity
import com.example.vigia.search.SearchDatabase
import com.example.vigia.search.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.vigia.BuildConfig
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MainViewState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private lateinit var router: RouterAgent
    private lateinit var perceptionManager: PerceptionManager
    private lateinit var telemetryManager: AzureIoTManager
    private lateinit var searchRepo: SearchRepository
    private lateinit var offlineManager: OfflineManager
    private lateinit var routeRepository: RouteRepository

    private var aiPilot: RoadPilotAgent? = null
    private var standardPilot: StandardPilotAgent? = null

    private var searchJob: Job? = null
    private var telemetryJob: Job? = null
    private var hazardCollectJob: Job? = null

    fun boot(isLiteMode: Boolean) {
        _state.value = _state.value.copy(isLiteMode = isLiteMode)

        // init DB/repositories
        viewModelScope.launch(Dispatchers.IO) {
            val db = SearchDatabase.Companion.getDatabase(getApplication(), viewModelScope)
            searchRepo = SearchRepository(getApplication(), db.placeDao())
            offlineManager =
                OfflineManager(getApplication(), searchRepo, db.placeDao(), db.hazardDao())
            routeRepository = RouteRepository(db.hazardDao())
        }

        // init perception + telemetry manager
        viewModelScope.launch(Dispatchers.Default) {
            perceptionManager = PerceptionManager(getApplication(), viewModelScope)

            val ehString =
                BuildConfig.AZURE_EVENTHUB_CONN

            try {
                telemetryManager = AzureIoTManager(ehString)
                Log.d("VigiaCloud", "Azure IoT Manager Initialized")
            } catch (e: Exception) {
                Log.e("VigiaCloud", "Failed to Init IoT Manager", e)
            }
        }
    }

    fun onSearchQueryChanged(queryRaw: String, currentLat: Double, currentLon: Double) {
        val query = queryRaw.trim()
        searchJob?.cancel()

        if (query.length < 2) {
            if (_state.value.uiState == MainUiState.SEARCHING) {
                _state.value = _state.value.copy(uiState = MainUiState.IDLE, searchResults = emptyList())
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(400)
            performSearch(query, currentLat, currentLon)
        }
    }

    private fun performSearch(query: String, currentLat: Double, currentLon: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!::searchRepo.isInitialized) return@launch
            val results = searchRepo.search(query, currentLat, currentLon)
            withContext(Dispatchers.Main) {
                if (results.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        uiState = MainUiState.SEARCHING,
                        searchResults = results
                    )
                } else {
                    _state.value = _state.value.copy(
                        uiState = MainUiState.IDLE,
                        searchResults = emptyList()
                    )
                }
            }
        }
    }

    fun onPlaceSelected(place: PlaceEntity, currentLat: Double, currentLon: Double) {
        _state.value = _state.value.copy(
            uiState = MainUiState.ROUTE_PREVIEW,
            destinationName = place.name,
            fastestTimeText = "...",
            safestTimeText = "...",
        )

        viewModelScope.launch(Dispatchers.IO) {
            val (fastest, safest) =
                routeRepository.calculateRoutes(currentLat, currentLon, place.lat, place.lon)

            withContext(Dispatchers.Main) {
                val fMin = fastest.durationSeconds / 60
                val sMin = safest.durationSeconds / 60

                _state.value = _state.value.copy(
                    fastestRoute = fastest,
                    safestRoute = safest,
                    selectedRoute = fastest,
                    isFastestSelected = true,
                    fastestTimeText = "$fMin min",
                    fastestCostText = "Est. Wear: $${fastest.damageCost.toInt()}",
                    safestTimeText = "$sMin min",
                    safestCostText = "Est. Wear: $${safest.damageCost.toInt()}",
                )
            }
        }
    }

    fun selectFastestRoute() {
        val fastest = _state.value.fastestRoute ?: return
        _state.value = _state.value.copy(
            selectedRoute = fastest,
            isFastestSelected = true
        )
    }

    fun selectSafestRoute() {
        val safest = _state.value.safestRoute ?: return
        _state.value = _state.value.copy(
            selectedRoute = safest,
            isFastestSelected = false
        )
    }

    fun closeRoute() {
        _state.value = _state.value.copy(
            uiState = MainUiState.IDLE,
            destinationName = "",
            fastestTimeText = "...",
            safestTimeText = "...",
            fastestCostText = "",
            safestCostText = "",
            fastestRoute = null,
            safestRoute = null,
            selectedRoute = null,
            searchResults = emptyList()
        )
    }

    fun startEngines() {
        viewModelScope.launch {
            _state.value = _state.value.copy(agentLog = "Initializing Neural Engines...")

            withContext(Dispatchers.Default) {
                try {
                    router = RouterAgent(getApplication())
                    Log.d("Vigia", "✅ Router Loaded")

                    if (_state.value.isLiteMode) {
                        standardPilot = StandardPilotAgent(getApplication())
                        Log.d("Vigia", "✅ Lite Pilot Loaded (Rules)")
                    } else {
                        aiPilot = RoadPilotAgent(getApplication())
                        Log.d("Vigia", "✅ AI Pilot Loaded (Phi-3.5)")
                    }

                } catch (e: Exception) {
                    Log.e("Vigia", "AI Load Failed", e)
                }
            }

            perceptionManager.start()
            startHazardCollector()
            startTelemetryLoop()

            if (::router.isInitialized) {
                _state.value = _state.value.copy(
                    isAiReady = true,
                    agentLog = "VIGIA Cortex Online. Tap mic to ask."
                )
            }
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (::telemetryManager.isInitialized) {
                    try {
                        val hs = _state.value.hazardState
                        telemetryManager.sendTelemetry(
                            /* lat = */ lastLat,
                            /* lon = */ lastLon,
                            /* speed = */ lastSpeed,
                            /* hazardType = */ if (hs.hasHazard) hs.type else "none",
                            /* confidence = */ hs.confidence
                        )
                    } catch (e: Exception) {
                        Log.e("VigiaCloud", "Telemetry Failed (Likely Network)", e)
                    }
                }
                delay(5000)
            }
        }
    }

    private fun startHazardCollector() {
        hazardCollectJob?.cancel()
        hazardCollectJob = viewModelScope.launch {
            perceptionManager.hazardState.collect { hs ->
                _state.value = _state.value.copy(hazardState = hs)
                if (hs.hasHazard) {
                    _events.tryEmit(MainUiEvent.AddHazardMarker(lastLat, lastLon))
                }
            }
        }
    }

    fun startOfflineDownload(currentLat: Double, currentLon: Double) {
        viewModelScope.launch {
            offlineManager.downloadArea(currentLat, currentLon) { status ->
                if (status == "Offline Area Ready!") {
                    _events.tryEmit(MainUiEvent.OfflineAreaReady)
                    plotOfflineHazards()
                }
            }
        }
    }

    private fun plotOfflineHazards() {
        viewModelScope.launch(Dispatchers.IO) {
            val db = SearchDatabase.Companion.getDatabase(getApplication(), viewModelScope)
            val hazards = db.hazardDao().getAllHazards()
            hazards.forEach { h ->
                _events.tryEmit(MainUiEvent.AddHazardMarker(h.lat, h.lon))
            }
        }
    }

    fun onSpeakClicked() {
        if (!::router.isInitialized) return
        _state.value = _state.value.copy(isListening = true)

        viewModelScope.launch {
            delay(1500)
            processInput("Is the road to my work safe? based on historical records?")
        }
    }

    private suspend fun processInput(text: String) {
        if (!::router.isInitialized) return

        val decision = withContext(Dispatchers.Default) {
            router.route(text, lastSpeed, _state.value.hazardState.hasHazard, true)
        }

        withContext(Dispatchers.Main) {
            _state.value = _state.value.copy(isListening = false)
            Log.i(
                "VigiaRouter",
                "⚡ ROUTER DECISION: $decision | Input: '$text' | LiteMode: ${_state.value.isLiteMode}"
            )

            fun performCloudQuery(updateScreen: Boolean) {
                val hs = _state.value.hazardState
                val contextData = mapOf(
                    "speed" to lastSpeed,
                    "lat" to lastLat,
                    "lon" to lastLon,
                    "hazard" to if (hs.hasHazard) hs.type else "none"
                )

                if (updateScreen) _state.value =
                    _state.value.copy(agentLog = "Connecting to Cloud...")
                CloudAgentClient.sendQuery(text, contextData) { reply ->
                    if (updateScreen) {
                        _state.value = _state.value.copy(agentLog = reply)
                    } else {
                        Log.d("VigiaCloud", "Background Reply: $reply")
                    }
                }
            }

            when (decision) {
                "AGENT_SAFETY" -> {
                    _events.tryEmit(MainUiEvent.Toast("⚠️ Safety Override!"))
                    _state.value =
                        _state.value.copy(agentLog = "⚠️ Safety Override: Watch the road!")
                    Log.w("Vigia", "Running Cloud Query in Background due to Safety Override...")
                    performCloudQuery(updateScreen = false)
                }

                "AGENT_LOCAL" -> {
                    if (_state.value.isLiteMode) {
                        Log.i("Vigia", "Basic System Detected: Rerouting LOCAL request to CLOUD.")
                        performCloudQuery(updateScreen = true)
                    } else {
                        _state.value = _state.value.copy(agentLog = "Router: Local Agent Selected.")
                        val response = aiPilot?.decideAction(text) ?: "Error: AI not ready."
                        _state.value = _state.value.copy(agentLog = response)
                    }
                }

                "AGENT_CLOUD" -> performCloudQuery(updateScreen = true)
            }
        }
    }

    // --- these match your activity variables, but owned by VM for telemetry/processInput ---
    private var lastSpeed: Float = 0f
    private var lastLat: Double = 47.6423
    private var lastLon: Double = -122.1369

    fun onLocationUpdate(lat: Double, lon: Double, speedKmh: Float) {
        lastLat = lat
        lastLon = lon
        lastSpeed = speedKmh
    }

    fun stop() {
        telemetryJob?.cancel()
        hazardCollectJob?.cancel()
        if (::perceptionManager.isInitialized) {
            viewModelScope.launch { perceptionManager.stop() }
        }
        if (::router.isInitialized) router.close()
    }
}