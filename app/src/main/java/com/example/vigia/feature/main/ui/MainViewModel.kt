package com.example.vigia.feature.main.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vigia.AzureIoTManager
import com.example.vigia.BuildConfig
import com.example.vigia.CloudAgentClient
import com.example.vigia.RouterAgent
import com.example.vigia.agents.RoadPilotAgent
import com.example.vigia.agents.StandardPilotAgent
import com.example.vigia.feature.main.model.MainUiEvent
import com.example.vigia.feature.main.model.MainUiState
import com.example.vigia.feature.main.model.MainViewState
import com.example.vigia.perception.PerceptionManager
import com.example.vigia.route.RoutePoint
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
import kotlin.math.cos
import kotlin.math.hypot

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

    // ---------------- Navigation / Reroute State ----------------
    private var navActive = false
    private var destinationLat: Double? = null
    private var destinationLon: Double? = null

    private var offRouteStrikes = 0
    private var lastRerouteAtMs = 0L

    private val OFF_ROUTE_THRESHOLD_M = 35.0
    private val OFF_ROUTE_STRIKES = 3
    private val REROUTE_COOLDOWN_MS = 12_000L
    private val EARTH_RADIUS_M = 6371000.0

    // ---------------- Small “await init” helpers ----------------
    private suspend fun awaitRouteRepoReady(timeoutMs: Long = 2500L): Boolean {
        val step = 50L
        var waited = 0L
        while (waited < timeoutMs) {
            if (::routeRepository.isInitialized) return true
            delay(step)
            waited += step
        }
        return ::routeRepository.isInitialized
    }

    private suspend fun awaitOfflineManagerReady(timeoutMs: Long = 2500L): Boolean {
        val step = 50L
        var waited = 0L
        while (waited < timeoutMs) {
            if (::offlineManager.isInitialized) return true
            delay(step)
            waited += step
        }
        return ::offlineManager.isInitialized
    }

    private suspend fun awaitPerceptionReady(timeoutMs: Long = 2500L): Boolean {
        val step = 50L
        var waited = 0L
        while (waited < timeoutMs) {
            if (::perceptionManager.isInitialized) return true
            delay(step)
            waited += step
        }
        return ::perceptionManager.isInitialized
    }

    // ---------------- Heading mode ----------------
    fun isHeadingUpEnabled(): Boolean = _state.value.headingUpEnabled

    fun toggleHeadingUp() {
        _state.value = _state.value.copy(headingUpEnabled = !_state.value.headingUpEnabled)
    }

    fun setHeadingUpEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(headingUpEnabled = enabled)
    }

    // ---------------- Navigation ----------------
    fun startNavigation() {
        val destLat = destinationLat
        val destLon = destinationLon
        if (destLat == null || destLon == null) {
            _events.tryEmit(MainUiEvent.Toast("Pick a destination first"))
            return
        }

        var chosen = _state.value.selectedRoute
        if (chosen == null) {
            chosen = if (_state.value.isFastestSelected) _state.value.fastestRoute else _state.value.safestRoute
            if (chosen != null) _state.value = _state.value.copy(selectedRoute = chosen)
        }

        if (chosen == null || chosen.points.isEmpty()) {
            _events.tryEmit(MainUiEvent.Toast("Route is still loading… try again"))
            return
        }

        navActive = true
        offRouteStrikes = 0
        lastRerouteAtMs = 0L

        val destName = _state.value.destinationName
        _state.value = _state.value.copy(
            isNavigating = true,
            uiState = MainUiState.IDLE,
            agentLog = if (destName.isNotBlank()) "Navigating to $destName…" else _state.value.agentLog
        )
    }

    fun stopNavigation() {
        navActive = false
        offRouteStrikes = 0
        _state.value = _state.value.copy(isNavigating = false)
    }

    // ---------------- Boot / init ----------------
    fun boot(isLiteMode: Boolean) {
        _state.value = _state.value.copy(isLiteMode = isLiteMode)

        viewModelScope.launch(Dispatchers.IO) {
            val db = SearchDatabase.getDatabase(getApplication(), viewModelScope)
            searchRepo = SearchRepository(getApplication(), db.placeDao())
            offlineManager = OfflineManager(getApplication(), searchRepo, db.placeDao(), db.hazardDao())
            routeRepository = RouteRepository(db.hazardDao())
        }

        viewModelScope.launch(Dispatchers.Default) {
            perceptionManager = PerceptionManager(getApplication(), viewModelScope)

            val ehString = BuildConfig.AZURE_EVENTHUB_CONN
            try {
                telemetryManager = AzureIoTManager(ehString)
                Log.d("VigiaCloud", "Azure IoT Manager Initialized")
            } catch (e: Exception) {
                Log.e("VigiaCloud", "Failed to Init IoT Manager", e)
            }
        }
    }

    // ---------------- Search ----------------
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
                _state.value = if (results.isNotEmpty()) {
                    _state.value.copy(uiState = MainUiState.SEARCHING, searchResults = results)
                } else {
                    _state.value.copy(uiState = MainUiState.IDLE, searchResults = emptyList())
                }
            }
        }
    }

    // ---------------- Route selection / preview ----------------
    fun onPlaceSelected(place: PlaceEntity, currentLat: Double, currentLon: Double) {
        destinationLat = place.lat
        destinationLon = place.lon

        navActive = false
        offRouteStrikes = 0
        _state.value = _state.value.copy(isNavigating = false)

        _state.value = _state.value.copy(
            uiState = MainUiState.ROUTE_PREVIEW,
            destinationName = place.name,
            fastestTimeText = "...",
            safestTimeText = "...",
        )

        viewModelScope.launch(Dispatchers.IO) {
            val ok = awaitRouteRepoReady()
            if (!ok) {
                _events.tryEmit(MainUiEvent.Toast("Route engine still initializing…"))
                return@launch
            }

            val (fastest, safest) = routeRepository.calculateRoutes(currentLat, currentLon, place.lat, place.lon)

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
        _state.value = _state.value.copy(selectedRoute = fastest, isFastestSelected = true)
        if (_state.value.isNavigating) {
            offRouteStrikes = 0
            lastRerouteAtMs = 0L
        }
    }

    fun selectSafestRoute() {
        val safest = _state.value.safestRoute ?: return
        _state.value = _state.value.copy(selectedRoute = safest, isFastestSelected = false)
        if (_state.value.isNavigating) {
            offRouteStrikes = 0
            lastRerouteAtMs = 0L
        }
    }

    fun closeRoute() {
        stopNavigation()
        destinationLat = null
        destinationLon = null

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

    // ---------------- Engines ----------------
    fun startEngines() {
        viewModelScope.launch {
            // orb should pulse while this is happening (isAiReady=false)
            _state.value = _state.value.copy(
                isAiReady = false,
                agentLog = "Loading models…"
            )

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

            val perceptionOk = awaitPerceptionReady()
            if (perceptionOk) {
                perceptionManager.start()
                startHazardCollector()
            } else {
                Log.e("Vigia", "PerceptionManager not ready; hazards disabled for now")
            }

            startTelemetryLoop()

            // ✅ Only mark ready when Router + chosen pilot are loaded
            val pilotReady = if (_state.value.isLiteMode) (standardPilot != null) else (aiPilot != null)
            val routerReady = ::router.isInitialized

            if (routerReady && pilotReady) {
                _state.value = _state.value.copy(
                    isAiReady = true,
                    agentLog = "VIGIA Cortex Online. Tap the orb to ask."
                )
            } else {
                _state.value = _state.value.copy(
                    isAiReady = false,
                    agentLog = "Model load incomplete. Please retry."
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
                            lastLat,
                            lastLon,
                            lastSpeed,
                            if (hs.hasHazard) hs.type else "none",
                            hs.confidence
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
                if (hs.hasHazard) _events.tryEmit(MainUiEvent.AddHazardMarker(lastLat, lastLon))
            }
        }
    }

    // ---------------- Offline ----------------
    fun startOfflineDownload(currentLat: Double, currentLon: Double) {
        viewModelScope.launch {
            val ok = awaitOfflineManagerReady()
            if (!ok) {
                _events.tryEmit(MainUiEvent.Toast("Offline engine still initializing…"))
                return@launch
            }

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
            val db = SearchDatabase.getDatabase(getApplication(), viewModelScope)
            val hazards = db.hazardDao().getAllHazards()
            hazards.forEach { h -> _events.tryEmit(MainUiEvent.AddHazardMarker(h.lat, h.lon)) }
        }
    }

    // ---------------- Copilot entry points ----------------

    /**
     * Called by the Gemini-style bottom drawer "Send" button.
     */
    fun onUserQuery(text: String) {
        if (!::router.isInitialized) return
        val q = text.trim()
        if (q.isBlank()) return

        // give immediate feedback even though the drawer closes
        _state.value = _state.value.copy(agentLog = "Thinking…")

        viewModelScope.launch {
            processInput(q)
        }
    }

    /**
     * Called by the drawer Mic button (still a stub without STT).
     * Keeps your demo working by sending a sample query.
     */
    fun onSpeakClicked() {
        if (!::router.isInitialized) return
        _state.value = _state.value.copy(isListening = true)

        viewModelScope.launch {
            delay(900)
            processInput("Is the road ahead safe right now based on hazard history?")
        }
    }

    private suspend fun processInput(text: String) {
        if (!::router.isInitialized) return

        val decision = withContext(Dispatchers.Default) {
            router.route(text, lastSpeed, _state.value.hazardState.hasHazard, true)
        }

        withContext(Dispatchers.Main) {
            _state.value = _state.value.copy(isListening = false)
            Log.i("VigiaRouter", "⚡ ROUTER DECISION: $decision | Input: '$text' | LiteMode: ${_state.value.isLiteMode}")

            fun performCloudQuery(updateScreen: Boolean) {
                val hs = _state.value.hazardState
                val contextData = mapOf(
                    "speed" to lastSpeed,
                    "lat" to lastLat,
                    "lon" to lastLon,
                    "bearing" to lastBearing,
                    "hazard" to if (hs.hasHazard) hs.type else "none"
                )

                if (updateScreen) _state.value = _state.value.copy(agentLog = "Connecting to Cloud…")

                CloudAgentClient.sendQuery(text, contextData) { reply ->
                    // ✅ ensure state updates happen on Main
                    viewModelScope.launch(Dispatchers.Main) {
                        if (updateScreen) _state.value = _state.value.copy(agentLog = reply)
                        else Log.d("VigiaCloud", "Background Reply: $reply")
                    }
                }
            }

            when (decision) {
                "AGENT_SAFETY" -> {
                    _events.tryEmit(MainUiEvent.Toast("⚠️ Safety Override!"))
                    _state.value = _state.value.copy(agentLog = "⚠️ Safety Override: Watch the road!")
                    performCloudQuery(updateScreen = false)
                }

                "AGENT_LOCAL" -> {
                    if (_state.value.isLiteMode) {
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

    // --- owned by VM for telemetry/processInput/reroute ---
    private var lastSpeed: Float = 0f
    private var lastLat: Double = 47.6423
    private var lastLon: Double = -122.1369
    private var lastBearing: Float = 0f

    fun onLocationUpdate(lat: Double, lon: Double, speedKmh: Float, bearingDeg: Float) {
        lastLat = lat
        lastLon = lon
        lastSpeed = speedKmh
        lastBearing = bearingDeg

        maybeRerouteIfOffRoute(lat, lon)
    }

    private fun maybeRerouteIfOffRoute(lat: Double, lon: Double) {
        if (!navActive) return
        if (!_state.value.isNavigating) return
        if (!::routeRepository.isInitialized) return

        val route = _state.value.selectedRoute ?: return
        val pts = route.points
        if (pts.size < 2) return

        val d = distanceToPolylineMeters(lat, lon, pts)
        if (d > OFF_ROUTE_THRESHOLD_M) offRouteStrikes++ else offRouteStrikes = 0

        val now = System.currentTimeMillis()
        if (offRouteStrikes >= OFF_ROUTE_STRIKES && now - lastRerouteAtMs > REROUTE_COOLDOWN_MS) {
            lastRerouteAtMs = now
            offRouteStrikes = 0

            val destLat = destinationLat ?: return
            val destLon = destinationLon ?: return

            viewModelScope.launch(Dispatchers.IO) {
                _events.tryEmit(MainUiEvent.Toast("↩️ Rerouting..."))

                val (fastest, safest) = routeRepository.calculateRoutes(lat, lon, destLat, destLon)

                withContext(Dispatchers.Main) {
                    val chosen = if (_state.value.isFastestSelected) fastest else safest
                    _state.value = _state.value.copy(
                        fastestRoute = fastest,
                        safestRoute = safest,
                        selectedRoute = chosen,
                        fastestTimeText = "${fastest.durationSeconds / 60} min",
                        fastestCostText = "Est. Wear: $${fastest.damageCost.toInt()}",
                        safestTimeText = "${safest.durationSeconds / 60} min",
                        safestCostText = "Est. Wear: $${safest.damageCost.toInt()}",
                    )
                }
            }
        }
    }

    private fun distanceToPolylineMeters(lat: Double, lon: Double, pts: List<RoutePoint>): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val d = distancePointToSegmentMeters(
                lat, lon,
                a.latitude, a.longitude,
                b.latitude, b.longitude
            )
            if (d < best) best = d
        }
        return best
    }

    private fun distancePointToSegmentMeters(
        pLat: Double, pLon: Double,
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double
    ): Double {
        val lat0 = Math.toRadians(pLat)
        fun x(lon: Double) = Math.toRadians(lon) * cos(lat0)
        fun y(lat: Double) = Math.toRadians(lat)

        val px = x(pLon); val py = y(pLat)
        val ax = x(aLon); val ay = y(aLat)
        val bx = x(bLon); val by = y(bLat)

        val dx = bx - ax
        val dy = by - ay
        val denom = (dx * dx + dy * dy).coerceAtLeast(1e-12)

        val t = ((px - ax) * dx + (py - ay) * dy) / denom
        val tc = t.coerceIn(0.0, 1.0)

        val cx = ax + tc * dx
        val cy = ay + tc * dy

        val distRad = hypot(px - cx, py - cy)
        return distRad * EARTH_RADIUS_M
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