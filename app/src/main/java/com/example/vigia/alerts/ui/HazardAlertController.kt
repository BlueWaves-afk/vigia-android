package com.example.vigia.alerts.ui

import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import com.example.vigia.R

class HazardAlertController(root: View) {

    private val banner: TextView = root.findViewById(R.id.hazardBanner)
    private var showing = false

    fun show(message: String, autoHideMs: Long = 1200L) {
        banner.text = message

        if (!showing) {
            showing = true
            banner.visibility = View.VISIBLE
            banner.startAnimation(AnimationUtils.loadAnimation(banner.context, R.anim.hazard_flash_in))
        }

        banner.removeCallbacks(hideRunnable)
        banner.postDelayed(hideRunnable, autoHideMs)
    }

    private val hideRunnable = Runnable { hide() }

    fun hide() {
        if (!showing) return
        showing = false

        val out = AnimationUtils.loadAnimation(banner.context, R.anim.hazard_flash_out)
        out.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation) {
                banner.visibility = View.GONE
            }
        })
        banner.startAnimation(out)
    }
}