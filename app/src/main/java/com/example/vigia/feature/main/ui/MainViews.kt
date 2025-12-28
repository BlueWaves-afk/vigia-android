package com.example.vigia.feature.main.ui

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.example.vigia.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.views.MapView

class MainViews(private val root: View) {

    private fun <T : View> opt(id: Int): T? = root.findViewById(id)

    // Map
    val map: MapView = root.findViewById(R.id.mapView)

    // Controls
    val btnRecenter: FloatingActionButton = root.findViewById(R.id.btnRecenter)
    val btnCompass: FloatingActionButton? = opt(R.id.btnCompass)
    val btnHeadingMode: FloatingActionButton? = opt(R.id.btnHeadingMode)

    // Speed pill (optional)
    val cardSpeed: CardView? = opt(R.id.cardSpeed)
    val txtSpeedPill: TextView? = opt(R.id.txtSpeedPill)

    // Search
    val cardSearch: CardView = root.findViewById(R.id.cardSearch)
    val inputSearch: EditText = root.findViewById(R.id.inputSearch)
    val btnDownloadMap: ImageView = root.findViewById(R.id.btnDownloadMap)

    val cardSearchResults: CardView = root.findViewById(R.id.cardSearchResults)
    val recyclerSearchResults: RecyclerView = root.findViewById(R.id.recyclerSearchResults)

    // Hazard status pill
    val cardHazard: CardView = root.findViewById(R.id.cardHazard)
    val txtHazardStatus: TextView = root.findViewById(R.id.txtHazardStatus)
    val imgHazardIcon: ImageView = root.findViewById(R.id.imgHazardIcon)

    // Route preview
    val cardRouteDetails: CardView = root.findViewById(R.id.cardRouteDetails)
    val txtRouteDestination: TextView = root.findViewById(R.id.txtRouteDestination)
    val btnCloseRoute: Button = root.findViewById(R.id.btnCloseRoute)
    val btnStartNavigation: Button = root.findViewById(R.id.btnStartNavigation)

    // Route option toggles
    val btnOptionFastest: LinearLayout = root.findViewById(R.id.btnOptionFastest)
    val btnOptionSafest: LinearLayout = root.findViewById(R.id.btnOptionSafest)
    val txtFastestTime: TextView = root.findViewById(R.id.txtFastestTime)
    val txtFastestCost: TextView = root.findViewById(R.id.txtFastestCost)
    val txtSafestTime: TextView = root.findViewById(R.id.txtSafestTime)
    val txtSafestCost: TextView = root.findViewById(R.id.txtSafestCost)
}