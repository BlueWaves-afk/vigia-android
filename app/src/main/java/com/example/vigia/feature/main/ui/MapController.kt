package com.example.vigia.feature.main.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.example.vigia.BuildConfig
import com.example.vigia.R
import com.example.vigia.route.RoutePoint
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import kotlin.math.abs

class MapController(
    private val context: Context,
    private val map: MapView
) {
    var userMarker: Marker? = null
    var activeRoutePolyline: Polyline? = null

    private var lastFollowCenter: GeoPoint? = null
    private var lastTilesetId: String? = null

    // Nav camera tuning (Google-like)
    private val navMinZoom = 18.0
    private val navLookAheadPx = 250  // positive -> pushes center down so you see more ahead

    // Smoothing knobs
    private val bearingSmoothing = 0.25f     // 0..1 (higher = faster)
    private val cameraMinMoveMeters = 3.0    // reduce micro-jitters
    private val cameraAnimMs = 450L

    private var smoothedBearing: Float = 0f
    private var routeAutoFittedOnce: Boolean = false

    @SuppressLint("ClickableViewAccessibility")
    fun setup(
        currentLat: Double,
        currentLon: Double,
        onUserTouch: () -> Unit
    ) {
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(currentLat, currentLon))

        val rotationGestureOverlay = RotationGestureOverlay(map).apply { isEnabled = true }
        map.overlays.add(rotationGestureOverlay)

        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                onUserTouch()
            }
            false
        }

        applyAzureTileSource()

        // IMPORTANT: remove any previous color filters (no inversion hacks)
        map.overlayManager.tilesOverlay.setColorFilter(null)
        map.invalidate()
    }

    /**
     * Fixes "random light tiles in dark map":
     * OSMDroid caches tiles by tile-source NAME.
     * If you keep the same name but switch tilesetId, cache can mix light/dark tiles.
     * We make the name tileset-specific + clear cache on change.
     */
    private fun applyAzureTileSource() {
        val azureKey = BuildConfig.AZURE_MAPS_KEY
        val night =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

        val tilesetId = if (night) "microsoft.base.darkgrey" else "microsoft.base.road"

        val tilesetChanged = lastTilesetId != null && lastTilesetId != tilesetId
        lastTilesetId = tilesetId

        val sourceName = "AzureMaps_${tilesetId.replace('.', '_')}"

        val azureTileSource = object : OnlineTileSourceBase(
            sourceName,
            0, 22, 256, "",
            arrayOf(
                "https://atlas.microsoft.com/map/tile?api-version=2.0&tilesetId=$tilesetId&subscription-key=$azureKey"
            )
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl +
                        "&zoom=" + MapTileIndex.getZoom(pMapTileIndex) +
                        "&x=" + MapTileIndex.getX(pMapTileIndex) +
                        "&y=" + MapTileIndex.getY(pMapTileIndex)
            }
        }

        map.setTileSource(azureTileSource)

        try {
            if (tilesetChanged) {
                map.tileProvider.clearTileCache()
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    /**
     * Backward-compatible: old callers still work.
     * If isNavigating=true, we:
     *  - enforce a higher zoom (nav feel)
     *  - offset the camera so user stays lower on screen (look-ahead)
     * Also adds smooth bearing + smoother follow camera.
     */
    fun updateUserLocation(
        lat: Double,
        lon: Double,
        isFollowingUser: Boolean,
        bearingDeg: Float,
        headingUp: Boolean,
        isNavigating: Boolean = false
    ) {
        val geoPoint = GeoPoint(lat, lon)

        if (userMarker == null) {
            userMarker = Marker(map).apply {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_navigation)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "You"
                isFlat = true
            }
            map.overlays.add(userMarker)

            // initialize smoothing from first value
            smoothedBearing = normalizeDeg(bearingDeg)
        }

        // Smooth bearing (shortest path)
        smoothedBearing = smoothAngle(smoothedBearing, normalizeDeg(bearingDeg), bearingSmoothing)

        userMarker?.position = geoPoint

        // Google-Maps-like behavior:
        // - headingUp = map rotates, arrow stays "up"
        // - northUp  = map fixed, arrow rotates
        if (headingUp && isFollowingUser) {
            // Smooth map rotation
            val current = normalizeDeg(map.mapOrientation)
            val next = smoothAngle(current, smoothedBearing, bearingSmoothing)
            map.mapOrientation = next
            userMarker?.rotation = 0f
        } else {
            userMarker?.rotation = smoothedBearing
        }

        if (isFollowingUser) {
            val prev = lastFollowCenter
            val movedEnough = (prev == null || prev.distanceToAsDouble(geoPoint) > cameraMinMoveMeters)

            if (movedEnough) {
                map.post {
                    // Navigation zoom lock (only while navigating)
                    if (isNavigating && map.zoomLevelDouble < navMinZoom) {
                        map.controller.setZoom(navMinZoom)
                    }

                    val targetCenter: GeoPoint = if (isNavigating) {
                        // Look-ahead camera: shift center so you see more road ahead
                        val proj = map.projection
                        val px = proj.toPixels(geoPoint, Point())
                        px.y += navLookAheadPx
                        (proj.fromPixels(px.x, px.y) as GeoPoint)
                    } else {
                        geoPoint
                    }

                    // Smooth camera move
                    try {
                        map.controller.animateTo(targetCenter, map.zoomLevelDouble, cameraAnimMs)
                    } catch (_: Exception) {
                        // fallback (some OSMDroid builds/devices may throw)
                        map.controller.setCenter(targetCenter)
                    }
                }

                lastFollowCenter = geoPoint
            }
        }

        map.invalidate()
    }

    fun addHazardMarker(lat: Double, lon: Double) {
        val hazardMarker = Marker(map).apply {
            position = GeoPoint(lat, lon)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_warning)
            title = "Hazard"
        }
        map.overlays.add(hazardMarker)
        map.invalidate()
    }

    fun drawRoute(points: List<RoutePoint>) {
        activeRoutePolyline?.let { map.overlays.remove(it) }

        val line = Polyline().apply {
            setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 20f
            outlinePaint.strokeCap = Paint.Cap.ROUND
        }

        map.overlays.add(0, line)
        activeRoutePolyline = line
        map.invalidate()

        // ✅ Auto-fit only once per route preview (prevents “zoom fight” during reroutes)
        if (!routeAutoFittedOnce && points.isNotEmpty()) {
            routeAutoFittedOnce = true
            try {
                map.zoomToBoundingBox(line.bounds, true, 50)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    fun clearRoute() {
        activeRoutePolyline?.let { map.overlays.remove(it) }
        activeRoutePolyline = null
        routeAutoFittedOnce = false
        map.invalidate()
    }

    // ---------- helpers ----------
    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    private fun shortestDelta(fromDeg: Float, toDeg: Float): Float {
        var delta = (toDeg - fromDeg) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun smoothAngle(currentDeg: Float, targetDeg: Float, alpha: Float): Float {
        val c = normalizeDeg(currentDeg)
        val t = normalizeDeg(targetDeg)
        val delta = shortestDelta(c, t)
        val next = c + delta * alpha
        return normalizeDeg(next)
    }
}