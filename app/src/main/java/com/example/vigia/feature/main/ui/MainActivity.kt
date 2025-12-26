package com.example.vigia.feature.main.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.preference.PreferenceManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vigia.CopilotActivity
import com.example.vigia.R
import com.example.vigia.feature.main.ui.KeyboardUtils
import com.example.vigia.feature.main.model.MainUiEvent
import com.example.vigia.feature.main.model.MainUiState
import com.example.vigia.feature.main.ui.MainViewModel
import com.example.vigia.feature.main.ui.MainViewModelFactory
import com.example.vigia.feature.main.ui.MainViews
import com.example.vigia.feature.main.ui.MapController
import com.example.vigia.feature.main.ui.UiAnimator
import com.example.vigia.search.SearchResultsAdapter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint

class MainActivity : AppCompatActivity() {

    private lateinit var views: MainViews
    private lateinit var mapController: MapController

    private lateinit var vm: MainViewModel

    private var isMapFollowingUser = true

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) startSystem()
            else Toast.makeText(this, "Permissions required for VIGIA", Toast.LENGTH_LONG).show()
        }

    private lateinit var searchAdapter: SearchResultsAdapter

    private var isProgrammaticTextUpdate = false

    private var currentLat = 47.6423
    private var currentLon = -122.1369
    private var currentSpeed = 0.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 100L * 1024 * 1024

        setContentView(R.layout.activity_main)
        views = MainViews(findViewById(android.R.id.content))

        // Force Copilot visible immediately (same as your code)
        views.bottomSheetCopilot.visibility = View.VISIBLE
        views.bottomSheetCopilot.alpha = 1.0f
        views.btnSpeak.isEnabled = false
        views.btnSpeak.alpha = 0.5f
        views.txtAgentLog.text = "Initializing AI..."

        vm = ViewModelProvider(this, MainViewModelFactory(application)).get(MainViewModel::class.java)

        val isLiteMode = intent.getBooleanExtra("IS_LITE_MODE", false)
        vm.boot(isLiteMode)

        mapController = MapController(this, views.map)
        setupMap()

        setupSearchRecycler()
        setupUiListeners()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    currentLat = location.latitude
                    currentLon = location.longitude
                    currentSpeed = location.speed * 3.6f

                    vm.onLocationUpdate(currentLat, currentLon, currentSpeed)

                    mapController.updateUserLocation(currentLat, currentLon, isMapFollowingUser)
                }
            }
        }

        collectVm()

        checkPermissionsAndStart()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        mapController.setup(
            currentLat = currentLat,
            currentLon = currentLon,
            onUserTouch = {
                isMapFollowingUser = false
                if (vm.state.value.uiState == MainUiState.SEARCHING) {
                    KeyboardUtils.hide(this, views.inputSearch)
                }
            }
        )
    }

    private fun setupSearchRecycler() {
        searchAdapter = SearchResultsAdapter { place ->
            isProgrammaticTextUpdate = true
            views.inputSearch.setText(place.name)
            views.inputSearch.clearFocus()
            KeyboardUtils.hide(this, views.inputSearch)
            isProgrammaticTextUpdate = false

            vm.onPlaceSelected(place, currentLat, currentLon)
        }

        views.recyclerSearchResults.layoutManager = LinearLayoutManager(this)
        views.recyclerSearchResults.adapter = searchAdapter
    }

    private fun setupUiListeners() {
        views.btnRecenter.setOnClickListener {
            isMapFollowingUser = true
            val point = GeoPoint(currentLat, currentLon)
            views.map.controller.animateTo(point, 17.0, 600L)
            views.map.mapOrientation = 0f
        }

        views.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isProgrammaticTextUpdate) return
                vm.onSearchQueryChanged(s?.toString() ?: "", currentLat, currentLon)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        views.btnDownloadMap.setOnClickListener {
            startOfflineDownloadUi()
            vm.startOfflineDownload(currentLat, currentLon)
        }

        views.btnOptionFastest.setOnClickListener {
            vm.selectFastestRoute()
        }

        views.btnOptionSafest.setOnClickListener {
            vm.selectSafestRoute()
        }

        views.btnCloseRoute.setOnClickListener {
            vm.closeRoute()
            isProgrammaticTextUpdate = true
            views.inputSearch.setText("")
            views.inputSearch.clearFocus()
            KeyboardUtils.hide(this, views.inputSearch)
            isProgrammaticTextUpdate = false
            mapController.clearRoute()
        }

        views.btnStartNavigation.setOnClickListener {
            isMapFollowingUser = true
            views.map.controller.animateTo(GeoPoint(currentLat, currentLon), 18.0, 800L)
            views.map.mapOrientation = 0f
            Toast.makeText(this, "🚀 Starting VIGIA Navigation...", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, CopilotActivity::class.java)
            intent.putExtra("IS_LITE_MODE", vm.state.value.isLiteMode)
            startActivity(intent)
        }

        views.btnSpeak.setOnClickListener {
            vm.onSpeakClicked()
        }
    }

    private fun collectVm() {
        lifecycleScope.launch {
            vm.state.collect { s ->
                // search list
                searchAdapter.updateList(s.searchResults)

                // route labels
                views.txtRouteDestination.text = s.destinationName
                views.txtFastestTime.text = s.fastestTimeText
                views.txtFastestCost.text = s.fastestCostText
                views.txtSafestTime.text = s.safestTimeText
                views.txtSafestCost.text = s.safestCostText

                // selection visuals (same as original)
                if (s.isFastestSelected) {
                    views.btnOptionFastest.alpha = 1.0f
                    views.btnOptionFastest.setBackgroundResource(R.drawable.bg_card_uber_selected)
                    views.btnOptionSafest.alpha = 0.5f
                    views.btnOptionSafest.setBackgroundResource(R.drawable.bg_card_uber)
                } else {
                    views.btnOptionSafest.alpha = 1.0f
                    views.btnOptionSafest.setBackgroundResource(R.drawable.bg_card_uber_selected)
                    views.btnOptionFastest.alpha = 0.5f
                    views.btnOptionFastest.setBackgroundResource(R.drawable.bg_card_uber)
                }

                // draw selected route when available
                s.selectedRoute?.let { mapController.drawRoute(it.points) }
                if (s.selectedRoute != null) isMapFollowingUser = false

                // hazard UI (same as original)
                if (s.hazardState.hasHazard) {
                    views.cardHazard.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.brandDanger))
                    views.txtHazardStatus.text = "HAZARD: ${s.hazardState.type.uppercase()}"
                    views.imgHazardIcon.setImageResource(R.drawable.ic_warning)
                } else {
                    views.cardHazard.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.colorSurface))
                    views.txtHazardStatus.text = "SYSTEM SAFE"
                    views.txtHazardStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textPrimary))
                    views.imgHazardIcon.setImageResource(R.drawable.ic_navigation)
                }

                // mic availability
                views.btnSpeak.isEnabled = s.isAiReady
                views.btnSpeak.alpha = if (s.isAiReady) 1.0f else 0.5f

                // agent log
                views.txtAgentLog.text = s.agentLog

                // listening animation (same as animateListeningState)
                if (s.isListening) {
                    views.btnSpeak.visibility = View.INVISIBLE
                    views.voiceVisualizer.visibility = View.VISIBLE
                    views.voiceVisualizer.playAnimation()
                    views.txtAgentLog.text = "Listening..."
                } else {
                    views.voiceVisualizer.cancelAnimation()
                    views.voiceVisualizer.visibility = View.INVISIBLE
                    views.btnSpeak.visibility = View.VISIBLE
                }

                // UI state machine (same as transitionToState)
                when (s.uiState) {
                    MainUiState.IDLE -> {
                        UiAnimator.animateView(views.bottomSheetCopilot, show = true)
                        UiAnimator.animateView(views.cardRouteDetails, show = false)
                        UiAnimator.animateView(views.cardSearchResults, show = false)
                        views.btnRecenter.show()
                    }
                    MainUiState.SEARCHING -> {
                        UiAnimator.animateView(views.cardSearchResults, show = true)
                        views.btnRecenter.hide()
                    }
                    MainUiState.ROUTE_PREVIEW -> {
                        UiAnimator.animateView(views.cardSearchResults, show = false)
                        UiAnimator.animateView(views.bottomSheetCopilot, show = false)
                        UiAnimator.animateView(views.cardRouteDetails, show = true)
                        views.btnRecenter.show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            vm.events.collect { e ->
                when (e) {
                    is MainUiEvent.Toast -> Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
                    is MainUiEvent.AddHazardMarker -> mapController.addHazardMarker(e.lat, e.lon)
                    MainUiEvent.OfflineAreaReady -> stopOfflineDownloadUi()
                }
            }
        }
    }

    private fun startOfflineDownloadUi() {
        Toast.makeText(this, "Downloading area...", Toast.LENGTH_SHORT).show()
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotate.duration = 1000
        rotate.repeatCount = Animation.INFINITE
        rotate.interpolator = LinearInterpolator()
        views.btnDownloadMap.startAnimation(rotate)
    }

    private fun stopOfflineDownloadUi() {
        views.btnDownloadMap.clearAnimation()
        views.btnDownloadMap.setColorFilter(Color.parseColor("#4CAF50"))
        Toast.makeText(this@MainActivity, "✓ Area Saved Offline", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissionsAndStart() {
        val required = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (required.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startSystem()
        } else {
            requestPermissionLauncher.launch(required)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSystem() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        vm.startEngines()
    }

    override fun onResume() {
        super.onResume()
        views.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        views.map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        vm.stop()
    }
}