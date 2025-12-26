package com.example.vigia.core.ui

import android.content.Context
import kotlin.math.roundToInt

fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).roundToInt()