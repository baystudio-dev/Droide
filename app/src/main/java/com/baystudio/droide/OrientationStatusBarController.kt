package com.baystudio.droide

import android.content.res.Configuration
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal enum class EditorStatusBarRequest {
    FOLLOW_ORIENTATION,
    IMMERSIVE_EDITOR,
    SHOW_IN_EDITOR;

    fun reclaimsTopCutout(orientation: Int): Boolean =
        this == IMMERSIVE_EDITOR && orientation == Configuration.ORIENTATION_PORTRAIT

    fun isHidden(orientation: Int): Boolean =
        this != SHOW_IN_EDITOR && (orientation == Configuration.ORIENTATION_LANDSCAPE || reclaimsTopCutout(orientation))
}

internal fun applyDroideOrientationStatusBar(window: Window, orientation: Int, request: EditorStatusBarRequest) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    val type = WindowInsetsCompat.Type.statusBars()
    if (request.isHidden(orientation)) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(type)
    } else {
        controller.show(type)
    }
}
