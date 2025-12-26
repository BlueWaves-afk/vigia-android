package com.example.vigia.feature.landing

import android.content.Context
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView

class DrawerController(
    private val context: Context,
    private val drawerLayout: DrawerLayout,
    private val navView: NavigationView,
) {

    fun open() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    fun close() {
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun isOpen(): Boolean = drawerLayout.isDrawerOpen(GravityCompat.START)

    fun attachDefaultMenuHandlers() {
        navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                com.example.vigia.R.id.nav_home -> close()
                com.example.vigia.R.id.nav_account -> {
                    Toast.makeText(context, "Account Settings", Toast.LENGTH_SHORT).show()
                    close()
                }
                com.example.vigia.R.id.nav_wallet -> {
                    Toast.makeText(context, "Wallet: $0.00", Toast.LENGTH_SHORT).show()
                    close()
                }
                else -> close()
            }
            true
        }
    }
}