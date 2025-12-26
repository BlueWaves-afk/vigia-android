package com.example.vigia.feature.landing

import android.content.Context
import android.view.View
import androidx.core.widget.NestedScrollView
import com.example.vigia.core.ui.dpToPx
import com.google.android.material.bottomsheet.BottomSheetBehavior

class BottomSheetController(
    private val context: Context,
    bottomSheet: NestedScrollView,
    private val searchContainer: View,
) {
    private val behavior: BottomSheetBehavior<NestedScrollView> = BottomSheetBehavior.from(bottomSheet).apply {
        state = BottomSheetBehavior.STATE_COLLAPSED
        // Critical: Allow a custom half-expanded ratio
        isFitToContents = false
    }

    fun collapse() {
        behavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    fun isExpandedOrHalfExpanded(): Boolean {
        return behavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED ||
                behavior.state == BottomSheetBehavior.STATE_EXPANDED
    }

    /**
     * Snaps the bottom sheet to just below the search bar.
     */
    fun expandBelowSearch() {
        searchContainer.post {
            val location = IntArray(2)
            searchContainer.getLocationOnScreen(location)

            val marginPx = 24.dpToPx(context)
            val searchBarBottomY = location[1] + searchContainer.height + marginPx

            val screenHeight = context.resources.displayMetrics.heightPixels
            val sheetHeight = screenHeight - searchBarBottomY
            val ratio = sheetHeight.toFloat() / screenHeight.toFloat()

            if (ratio in 0.1f..0.9f) {
                behavior.halfExpandedRatio = ratio
                behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            } else {
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }
}