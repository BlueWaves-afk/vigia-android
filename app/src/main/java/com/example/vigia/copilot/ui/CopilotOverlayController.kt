package com.example.vigia.copilot.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.annotation.IdRes
import com.example.vigia.R

class CopilotOverlayController(private val root: View) {

    private fun <T : View> opt(@IdRes id: Int): T? = root.findViewById(id)

    // Views from include_copilot_overlay.xml
    private val overlayRoot: View? = opt(R.id.copilotOverlayRoot)
    private val pulseRing: View? = opt(R.id.copilotPulseRing)
    private val orb: View? = opt(R.id.copilotOrb)
    private val orbIcon: ImageView? = opt(R.id.copilotOrbIcon)

    private var isReady: Boolean = false

    // Animations
    private var idleRotateAnimator: ObjectAnimator? = null
    private var orbLoadingPulse: AnimatorSet? = null
    private var ringPulse: AnimatorSet? = null

    // Callbacks
    private var onOrbClick: (() -> Unit)? = null
    private var onOrbLongPress: (() -> Unit)? = null

    init {
        pulseRing?.apply {
            visibility = View.GONE
            alpha = 0f
            scaleX = 1f
            scaleY = 1f
        }

        // default: show loading until VM says ready
        setReady(false)

        orb?.setOnClickListener {
            bounceOrb(it)
            onOrbClick?.invoke()
        }

        orb?.setOnLongClickListener {
            onOrbLongPress?.invoke()
            true
        }
    }

    fun setOnOrbClick(listener: () -> Unit) {
        onOrbClick = listener
    }

    fun setOnOrbLongPress(listener: () -> Unit) {
        onOrbLongPress = listener
    }

    /**
     * Call from MainActivity.collectVm():
     * copilotController.setReady(s.isAiReady)
     */
    fun setReady(ready: Boolean) {
        if (ready == isReady) return
        isReady = ready

        if (ready) {
            stopOrbLoadingPulse()
            stopRingPulse()
            startIdleRotate()
            orb?.alpha = 1.0f
            orbIcon?.alpha = 1.0f
        } else {
            stopIdleRotate()
            startRingPulse()
            startOrbLoadingPulse()
            orb?.alpha = 0.85f
            orbIcon?.alpha = 0.95f
        }
    }

    // ---------------- Animations ----------------

    private fun bounceOrb(v: View) {
        runCatching {
            val bounce = AnimationUtils.loadAnimation(v.context, R.anim.orb_bounce)
            v.startAnimation(bounce)
        }
    }

    private fun startIdleRotate() {
        val o = orb ?: return
        if (idleRotateAnimator?.isRunning == true) return

        idleRotateAnimator = ObjectAnimator.ofFloat(o, "rotation", o.rotation, o.rotation + 360f).apply {
            duration = 3400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    private fun stopIdleRotate() {
        idleRotateAnimator?.cancel()
        idleRotateAnimator = null
        orb?.rotation = 0f
    }

    private fun startOrbLoadingPulse() {
        val o = orb ?: return
        if (orbLoadingPulse?.isRunning == true) return

        val sx = ObjectAnimator.ofFloat(o, "scaleX", 1f, 1.08f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
        }
        val sy = ObjectAnimator.ofFloat(o, "scaleY", 1f, 1.08f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
        }
        val a = ObjectAnimator.ofFloat(o, "alpha", 0.70f, 0.95f, 0.70f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
        }

        orbLoadingPulse = AnimatorSet().apply {
            playTogether(sx, sy, a)
            start()
        }
    }

    private fun stopOrbLoadingPulse() {
        orbLoadingPulse?.cancel()
        orbLoadingPulse = null
        orb?.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
        }
    }

    private fun startRingPulse() {
        val ring = pulseRing ?: return
        if (ringPulse?.isRunning == true) return

        ring.visibility = View.VISIBLE
        ring.alpha = 0f
        ring.scaleX = 0.92f
        ring.scaleY = 0.92f

        val a = ObjectAnimator.ofFloat(ring, "alpha", 0f, 0.85f, 0f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
        }
        val sx = ObjectAnimator.ofFloat(ring, "scaleX", 0.92f, 1.10f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
        }
        val sy = ObjectAnimator.ofFloat(ring, "scaleY", 0.92f, 1.10f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
        }

        ringPulse = AnimatorSet().apply {
            playTogether(a, sx, sy)
            start()
        }
    }

    private fun stopRingPulse() {
        ringPulse?.cancel()
        ringPulse = null
        pulseRing?.apply {
            alpha = 0f
            scaleX = 1f
            scaleY = 1f
            visibility = View.GONE
        }
    }
}