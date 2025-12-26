package com.example.vigia.feature.landing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.NestedScrollView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vigia.CopilotActivity
import com.example.vigia.feature.main.ui.MainActivity
import com.example.vigia.R
import com.example.vigia.core.common.Extras
import com.example.vigia.core.ui.applyBounceTouch
import com.example.vigia.feature.landing.model.RecentTrip
import com.example.vigia.feature.landing.ui.RecentTripsAdapter
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationView

class LandingActivity : AppCompatActivity() {

    private var isLiteMode: Boolean = false

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerController: DrawerController
    private lateinit var bottomSheetController: BottomSheetController

    // Views
    private lateinit var btnMenu: ImageView
    private lateinit var inputSearch: EditText
    private lateinit var cardNav: MaterialCardView
    private lateinit var cardCopilot: MaterialCardView

    // Recent Trips
    private lateinit var rvRecentTrips: RecyclerView
    private lateinit var tripsAdapter: RecentTripsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_landing)

        applySystemBars()
        readExtras()
        bindViews()
        setupControllers()
        setupTripsList()
        setupInteractions()
        setupBackPress()
    }

    private fun applySystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
    }

    private fun readExtras() {
        isLiteMode = intent.getBooleanExtra(Extras.IS_LITE_MODE, false)
    }

    private fun bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout)

        val navView = findViewById<NavigationView>(R.id.navView)
        btnMenu = findViewById(R.id.btnMenu)
        inputSearch = findViewById(R.id.inputSearch)

        val searchContainer = findViewById<View>(R.id.searchContainer)
        val bottomSheet = findViewById<NestedScrollView>(R.id.bottomSheet)

        cardNav = findViewById(R.id.cardNavigation)
        cardCopilot = findViewById(R.id.cardCopilot)

        // RecyclerView in bottom sheet
        rvRecentTrips = findViewById(R.id.rvRecentTrips)

        // Controllers need these
        drawerController = DrawerController(this, drawerLayout, navView)
        bottomSheetController = BottomSheetController(this, bottomSheet, searchContainer)
    }

    private fun setupControllers() {
        drawerController.attachDefaultMenuHandlers()
    }

    private fun setupTripsList() {
        tripsAdapter = RecentTripsAdapter { trip ->
            // For now, keep it simple and judge-friendly.
            // Later: open navigation with this destination / prefill search.
            Toast.makeText(this, "Trip: ${trip.label} → ${trip.destination}", Toast.LENGTH_SHORT).show()

            // Optional UX: expand sheet and clear focus
            // bottomSheetController.expandBelowSearch()
            // inputSearch.clearFocus()
        }

        rvRecentTrips.layoutManager = LinearLayoutManager(this)
        rvRecentTrips.adapter = tripsAdapter

        // Demo data (replace with persisted recent trips later)
        tripsAdapter.submitList(
            listOf(
                RecentTrip(id = "work", label = "Work", destination = "Microsoft Campus, Redmond"),
                RecentTrip(id = "home", label = "Home", destination = "Kirkland, WA"),
            )
        )
    }

    private fun setupInteractions() {
        // Menu
        btnMenu.applyBounceTouch()
        btnMenu.setOnClickListener { drawerController.open() }

        // Search → expand bottom sheet
        inputSearch.setOnClickListener { bottomSheetController.expandBelowSearch() }
        inputSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) bottomSheetController.expandBelowSearch()
        }

        // Main cards
        cardNav.applyBounceTouch()
        cardNav.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(Extras.IS_LITE_MODE, isLiteMode)
            })
        }

        cardCopilot.applyBounceTouch()
        cardCopilot.setOnClickListener {
            startActivity(Intent(this, CopilotActivity::class.java).apply {
                putExtra(Extras.IS_LITE_MODE, isLiteMode)
            })
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawerController.isOpen() -> drawerController.close()
                    bottomSheetController.isExpandedOrHalfExpanded() -> {
                        bottomSheetController.collapse()
                        inputSearch.clearFocus()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }
}