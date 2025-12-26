package com.example.vigia.feature.main.ui

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

object UiAnimator {
    fun animateView(view: View, show: Boolean) {
        if (show && view.visibility == View.VISIBLE && view.alpha == 1f) return
        if (!show && view.visibility == View.GONE) return

        if (show) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.translationY = 50f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            view.animate()
                .alpha(0f)
                .translationY(50f)
                .setDuration(200)
                .withEndAction { view.visibility = View.GONE }
                .start()
        }
    }
}