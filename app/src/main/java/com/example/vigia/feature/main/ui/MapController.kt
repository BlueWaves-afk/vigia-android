package com.example.vigia.feature.main.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.example.vigia.R
import com.example.vigia.route.RoutePoint
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import com.example.vigia.BuildConfig
class MapController(
    private val context: Context,
    private val map: MapView
) {
    var userMarker: Marker? = null
    var activeRoutePolyline: Polyline? = null

    @SuppressLint("ClickableViewAccessibility")
    fun setup(
        currentLat: Double,
        currentLon: Double,
        onUserTouch: () -> Unit
    ) {
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        map.controller.setCenter(GeoPoint(currentLat, currentLon))

        val rotationGestureOverlay = RotationGestureOverlay(map)
        rotationGestureOverlay.isEnabled = true
        map.overlays.add(rotationGestureOverlay)

        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                onUserTouch()
            }
            false
        }

        // --- AZURE MAPS TILE SOURCE ---
        val azureKey = BuildConfig.AZURE_MAPS_KEY
        val azureTileSource = object : OnlineTileSourceBase(
            "AzureMaps",
            0, 22, 256, "",
            arrayOf("https://atlas.microsoft.com/map/tile?api-version=2.0&tilesetId=microsoft.base.road&subscription-key=$azureKey")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + "&zoom=" + MapTileIndex.getZoom(pMapTileIndex) +
                        "&x=" + MapTileIndex.getX(pMapTileIndex) +
                        "&y=" + MapTileIndex.getY(pMapTileIndex)
            }
        }
        map.setTileSource(azureTileSource)

        // --- DARK MODE THEME HANDLING ---
        val currentNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
            val inverseMatrix = ColorMatrix(
                floatArrayOf(
                    -1.0f, 0.0f, 0.0f, 0.0f, 255f,
                    0.0f, -1.0f, 0.0f, 0.0f, 255f,
                    0.0f, 0.0f, -1.0f, 0.0f, 255f,
                    0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                )
            )
            val destinationColor = ColorMatrix()
            destinationColor.setScale(0.9f, 0.9f, 0.9f, 1f)
            inverseMatrix.postConcat(destinationColor)

            map.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(inverseMatrix))
        } else {
            map.overlayManager.tilesOverlay.setColorFilter(null)
        }
    }

    fun updateUserLocation(
        lat: Double,
        lon: Double,
        isFollowingUser: Boolean
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
        }

        userMarker?.position = geoPoint

        if (isFollowingUser) {
            map.controller.animateTo(geoPoint, map.zoomLevelDouble, 400L)
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

        map.zoomToBoundingBox(line.bounds, true, 50)
    }

    fun clearRoute() {
        activeRoutePolyline?.let { map.overlays.remove(it) }
        activeRoutePolyline = null
        map.invalidate()
    }
}