package com.example.vigia.core.ui

import android.animation.ObjectAnimator
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

fun View.applyBounceTouch(scaleDown: Float = 0.96f) {
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                ObjectAnimator.ofFloat(v, "scaleX", scaleDown).apply {
                    duration = 100
                    interpolator = DecelerateInterpolator()
                    start()
                }
                ObjectAnimator.ofFloat(v, "scaleY", scaleDown).apply {
                    duration = 100
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                ObjectAnimator.ofFloat(v, "scaleX", 1f).apply {
                    duration = 200
                    interpolator = DecelerateInterpolator()
                    start()
                }
                ObjectAnimator.ofFloat(v, "scaleY", 1f).apply {
                    duration = 200
                    interpolator = DecelerateInterpolator()
                    start()
                }
            }
        }
        false
    }
}