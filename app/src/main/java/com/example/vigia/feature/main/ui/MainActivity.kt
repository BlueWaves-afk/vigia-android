package com.example.vigia.feature.main.ui

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.preference.PreferenceManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vigia.R
import com.example.vigia.feature.main.model.MainUiEvent
import com.example.vigia.feature.main.model.MainUiState
import com.example.vigia.search.SearchResultsAdapter
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import kotlin.math.abs

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

    // bearing tracking
    private var lastBearing: Float = 0f
    private var prevLatForBearing: Double? = null
    private var prevLonForBearing: Double? = null

    // current state
    private var currentLat = 47.6423
    private var currentLon = -122.1369
    private var currentSpeed = 0.0f

    // -------------------------------------------------------------------------
    // Copilot Orb (from include_copilot_overlay.xml) + Hazard banner overlay
    // -------------------------------------------------------------------------
    private var copilotOrb: View? = null            // R.id.copilotOrb
    private var hazardBanner: TextView? = null      // R.id.hazardBanner
    private var hazardBannerJob: Job? = null

    // Prevent banner spam on every state update
    private var lastHazardKeyShown: String? = null

    // -------------------------------------------------------------------------
    // Gemini-style bottom drawer (BottomSheetDialog)
    // -------------------------------------------------------------------------
    private var copilotDialog: BottomSheetDialog? = null
    private var copilotDrawerView: View? = null

    private var drawerGreeting: TextView? = null
    private var drawerStatus: TextView? = null
    private var drawerInput: EditText? = null
    private var drawerMic: ImageView? = null
    private var drawerSend: ImageView? = null
    private var drawerClose: ImageView? = null

    // Orb pulsing animator (loading)
    private var orbPulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().tileFileSystemCacheMaxBytes = 100L * 1024 * 1024

        setContentView(R.layout.activity_main)
        views = MainViews(findViewById(android.R.id.content))

        vm = ViewModelProvider(this, MainViewModelFactory(application))
            .get(MainViewModel::class.java)

        val isLiteMode = intent.getBooleanExtra("IS_LITE_MODE", false)
        vm.boot(isLiteMode)

        mapController = MapController(this, views.map)
        setupMap()

        setupSearchRecycler()
        setupUiListeners()

        bindOverlayViews()
        setupCopilotDrawer()
        setupOrbListeners()

        // Reposition right-side controls under SYSTEM SAFE (top-right)
        views.map.doOnLayout { positionRightControlsUnderHazardPill() }

        // Map padding so the "You" arrow is not hidden behind route preview
        views.cardRouteDetails.doOnLayout { updateMapPadding() }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    currentLat = location.latitude
                    currentLon = location.longitude
                    currentSpeed = location.speed * 3.6f

                    views.txtSpeedPill?.text = "${currentSpeed.toInt()} km/h"

                    val bearing = when {
                        location.hasBearing() -> location.bearing
                        prevLatForBearing != null && prevLonForBearing != null ->
                            bearingBetween(
                                prevLatForBearing!!,
                                prevLonForBearing!!,
                                currentLat,
                                currentLon
                            )
                        else -> lastBearing
                    }

                    lastBearing = bearing
                    prevLatForBearing = currentLat
                    prevLonForBearing = currentLon

                    vm.onLocationUpdate(currentLat, currentLon, currentSpeed, bearing)

                    val isNavigating = vm.state.value.isNavigating
                    mapController.updateUserLocation(
                        lat = currentLat,
                        lon = currentLon,
                        isFollowingUser = isMapFollowingUser,
                        bearingDeg = bearing,
                        headingUp = vm.isHeadingUpEnabled(),
                        isNavigating = isNavigating
                    )

                    val rotated = abs(views.map.mapOrientation) > 1f
                    views.btnCompass?.visibility = if (rotated) View.VISIBLE else View.GONE
                }
            }
        }

        collectVm()
        checkPermissionsAndStart()
    }

    // -----------------------------
    // Overlay / Orb
    // -----------------------------
    private fun bindOverlayViews() {
        copilotOrb = findViewById(R.id.copilotOrb)
        hazardBanner = findViewById(R.id.hazardBanner)
        hazardBanner?.visibility = View.GONE
    }

    private fun setupOrbListeners() {
        copilotOrb?.setOnClickListener {
            bounce(it)
            openCopilotDrawer()
        }
    }

    // Orb pulses while models are loading
    private fun setOrbLoading(loading: Boolean) {
        val orb = copilotOrb ?: return

        if (loading) {
            if (orbPulse?.isRunning == true) return

            orbPulse = ValueAnimator.ofFloat(0.92f, 1.08f).apply {
                duration = 850
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val s = anim.animatedValue as Float
                    orb.scaleX = s
                    orb.scaleY = s
                    orb.alpha = 0.85f
                }
                start()
            }
        } else {
            orbPulse?.cancel()
            orbPulse = null
            orb.scaleX = 1f
            orb.scaleY = 1f
            orb.alpha = 1f
        }
    }

    private fun setupCopilotDrawer() {
        copilotDialog = BottomSheetDialog(
            this,
            com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog
        )
        val v = layoutInflater.inflate(R.layout.dialog_copilot_sheet, null, false)
        copilotDialog?.setContentView(v)
        copilotDrawerView = v

        drawerGreeting = v.findViewById(R.id.copilotDrawerGreeting)
        drawerStatus = v.findViewById(R.id.copilotDrawerStatus)
        drawerInput = v.findViewById(R.id.copilotDrawerInput)
        drawerMic = v.findViewById(R.id.copilotDrawerMic)
        drawerSend = v.findViewById(R.id.copilotDrawerSend)
        drawerClose = v.findViewById(R.id.copilotDrawerClose)

        drawerClose?.setOnClickListener { copilotDialog?.dismiss() }

        drawerMic?.setOnClickListener {
            val ready = vm.state.value.isAiReady
            if (!ready) {
                Toast.makeText(this, "Initializing AI…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.onSpeakClicked()
        }

        fun sendTypedQuery() {
            val text = drawerInput?.text?.toString().orEmpty().trim()
            if (text.isBlank()) return

            val ready = vm.state.value.isAiReady
            if (!ready) {
                Toast.makeText(this, "Initializing AI…", Toast.LENGTH_SHORT).show()
                return
            }

            vm.onUserQuery(text)
            drawerInput?.setText("")
        }

        drawerSend?.setOnClickListener { sendTypedQuery() }

        drawerInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendTypedQuery()
                true
            } else false
        }
    }

    private fun openCopilotDrawer() {
        val ready = vm.state.value.isAiReady

        drawerGreeting?.text = if (ready) {
            "Hello 👋 How may I help you today?"
        } else {
            "Loading VIGIA…"
        }

        // show latest status/log under the greeting (Gemini-like “Thinking/Status” line)
        val log = vm.state.value.agentLog
        drawerStatus?.text = if (ready) log else "Models initializing…"

        // disable input until ready
        drawerInput?.isEnabled = ready
        drawerMic?.alpha = if (ready) 1.0f else 0.55f
        drawerSend?.alpha = if (ready) 1.0f else 0.55f

        copilotDialog?.show()
    }

    private fun bounce(v: View) {
        v.animate().cancel()
        v.scaleX = 1f
        v.scaleY = 1f
        v.animate()
            .scaleX(1.08f).scaleY(1.08f)
            .setDuration(90)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    // Move right-side map controls to top-right below SYSTEM SAFE pill
    private fun positionRightControlsUnderHazardPill() {
        val anchorBottom = views.cardHazard.bottom + dp(10)

        fun moveTopRight(v: View?, extraTop: Int) {
            if (v == null) return
            val lp = v.layoutParams

            when (lp) {
                is ViewGroup.MarginLayoutParams -> {
                    if (lp is android.widget.FrameLayout.LayoutParams) {
                        (lp as android.widget.FrameLayout.LayoutParams).gravity = Gravity.TOP or Gravity.END
                    }
                    lp.topMargin = anchorBottom + extraTop
                    lp.marginEnd = dp(14)
                    v.layoutParams = lp
                }
            }
        }

        moveTopRight(views.btnRecenter, extraTop = 0)
        moveTopRight(views.btnCompass, extraTop = dp(64))
        moveTopRight(views.btnHeadingMode, extraTop = dp(128))
    }

    private fun showHazardBanner(message: String) {
        val banner = hazardBanner ?: return
        banner.text = message

        hazardBannerJob?.cancel()
        hazardBannerJob = lifecycleScope.launch {
            banner.visibility = View.VISIBLE
            banner.alpha = 0f

            val h = if (banner.height > 0) banner.height.toFloat() else dp(56).toFloat()
            banner.translationY = -h

            banner.animate().translationY(0f).alpha(1f).setDuration(180).start()
            delay(1200)
            banner.animate()
                .translationY(-h)
                .alpha(0f)
                .setDuration(160)
                .withEndAction { banner.visibility = View.GONE }
                .start()
        }
    }

    // -----------------------------
    // Map + UI
    // -----------------------------
    @SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        mapController.setup(
            currentLat = currentLat,
            currentLon = currentLon,
            onUserTouch = {
                isMapFollowingUser = false
                views.btnRecenter.show()

                if (vm.state.value.uiState == MainUiState.SEARCHING) {
                    KeyboardUtils.hide(this, views.inputSearch)
                }
            }
        )

        views.btnCompass?.visibility = View.GONE
        views.btnHeadingMode?.alpha = if (vm.isHeadingUpEnabled()) 1.0f else 0.6f
        views.txtSpeedPill?.text = "0 km/h"
    }

    private fun updateMapPadding() {
        val bottomInset =
            (if (views.cardRouteDetails.visibility == View.VISIBLE) views.cardRouteDetails.height else 0) + dp(24)

        val topInset = dp(72)
        views.map.setPadding(0, topInset, 0, bottomInset)
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
            views.map.controller.animateTo(point, 17.0, 450L)

            if (!vm.isHeadingUpEnabled()) {
                views.map.mapOrientation = 0f
            }
        }

        views.btnCompass?.setOnClickListener {
            views.map.mapOrientation = 0f
            vm.setHeadingUpEnabled(false)
            views.btnHeadingMode?.alpha = 0.6f
            views.btnCompass?.visibility = View.GONE
        }

        views.btnHeadingMode?.setOnClickListener {
            val newValue = !vm.isHeadingUpEnabled()
            vm.setHeadingUpEnabled(newValue)
            views.btnHeadingMode?.alpha = if (newValue) 1.0f else 0.6f

            if (!newValue) {
                views.map.mapOrientation = 0f
                views.btnCompass?.visibility = View.GONE
            }
            isMapFollowingUser = true
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

        views.btnOptionFastest.setOnClickListener { vm.selectFastestRoute() }
        views.btnOptionSafest.setOnClickListener { vm.selectSafestRoute() }

        views.btnCloseRoute.setOnClickListener {
            vm.stopNavigation()
            vm.closeRoute()

            isProgrammaticTextUpdate = true
            views.inputSearch.setText("")
            views.inputSearch.clearFocus()
            KeyboardUtils.hide(this, views.inputSearch)
            isProgrammaticTextUpdate = false

            mapController.clearRoute()

            isMapFollowingUser = true
            views.map.mapOrientation = 0f
        }

        views.btnStartNavigation.setOnClickListener {
            vm.startNavigation()

            isMapFollowingUser = true
            views.map.controller.animateTo(GeoPoint(currentLat, currentLon), 18.5, 650L)

            if (!vm.isHeadingUpEnabled()) {
                views.map.mapOrientation = 0f
            }

            Toast.makeText(this, "🚗 Navigation started", Toast.LENGTH_SHORT).show()
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

                // selection visuals
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

                // Follow logic (preview lets user inspect)
                if (s.selectedRoute != null && !s.isNavigating) isMapFollowingUser = false

                // hazard UI pill
                if (s.hazardState.hasHazard) {
                    views.cardHazard.setCardBackgroundColor(
                        ContextCompat.getColor(this@MainActivity, R.color.brandDanger)
                    )
                    views.txtHazardStatus.text = "HAZARD: ${s.hazardState.type.uppercase()}"
                    views.imgHazardIcon.setImageResource(R.drawable.ic_warning)

                    val key = "1:${s.hazardState.type}"
                    if (lastHazardKeyShown != key) {
                        lastHazardKeyShown = key
                        showHazardBanner("⚠ ${s.hazardState.type.uppercase()} DETECTED")
                    }
                } else {
                    views.cardHazard.setCardBackgroundColor(
                        ContextCompat.getColor(this@MainActivity, R.color.colorSurface)
                    )
                    views.txtHazardStatus.text = "SYSTEM SAFE"
                    views.txtHazardStatus.setTextColor(
                        ContextCompat.getColor(this@MainActivity, R.color.textPrimary)
                    )
                    views.imgHazardIcon.setImageResource(R.drawable.ic_navigation)
                    lastHazardKeyShown = "0"
                }

                // ✅ Orb loading pulse
                setOrbLoading(!s.isAiReady)

                // If drawer is open, keep its status text synced
                if (copilotDialog?.isShowing == true) {
                    drawerStatus?.text = if (s.isListening) "Listening…" else s.agentLog
                    val ready = s.isAiReady
                    drawerInput?.isEnabled = ready
                    drawerMic?.alpha = if (ready) 1.0f else 0.55f
                    drawerSend?.alpha = if (ready) 1.0f else 0.55f
                }

                // UI state machine
                when (s.uiState) {
                    MainUiState.IDLE -> {
                        UiAnimator.animateView(views.cardRouteDetails, show = false)
                        UiAnimator.animateView(views.cardSearchResults, show = false)
                        views.btnRecenter.show()
                        updateMapPadding()
                    }

                    MainUiState.SEARCHING -> {
                        UiAnimator.animateView(views.cardSearchResults, show = true)
                        views.btnRecenter.hide()
                        updateMapPadding()
                    }

                    MainUiState.ROUTE_PREVIEW -> {
                        UiAnimator.animateView(views.cardSearchResults, show = false)
                        UiAnimator.animateView(views.cardRouteDetails, show = true)
                        views.btnRecenter.show()
                        updateMapPadding()
                    }
                }

                // re-position controls if hazard pill changes height/layout
                views.map.doOnLayout { positionRightControlsUnderHazardPill() }
            }
        }

        lifecycleScope.launch {
            vm.events.collect { e ->
                when (e) {
                    is MainUiEvent.Toast ->
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()

                    is MainUiEvent.AddHazardMarker ->
                        mapController.addHazardMarker(e.lat, e.lon)

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
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(1000)
            .setMaxUpdateDelayMillis(1000)
            .build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

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
        orbPulse?.cancel()
        orbPulse = null
        copilotDialog?.dismiss()
        copilotDialog = null
    }

    private fun bearingBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val φ1 = Math.toRadians(lat1)
        val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)

        val y = Math.sin(Δλ) * Math.cos(φ2)
        val x = Math.cos(φ1) * Math.sin(φ2) -
                Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ)

        val θ = Math.atan2(y, x)
        val deg = (Math.toDegrees(θ) + 360.0) % 360.0
        return deg.toFloat()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}