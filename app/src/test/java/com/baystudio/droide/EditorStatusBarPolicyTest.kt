package com.baystudio.droide

import android.app.Activity
import android.app.Application
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class EditorStatusBarPolicyTest {
    @Test fun orientationDefaultsAndTheVisibleOverrideAgreeWithCutoutOwnership() {
        val outside = EditorStatusBarRequest.FOLLOW_ORIENTATION
        val immersive = EditorStatusBarRequest.IMMERSIVE_EDITOR
        val visible = EditorStatusBarRequest.SHOW_IN_EDITOR
        assertFalse(outside.isHidden(ORIENTATION_PORTRAIT))
        assertTrue(outside.isHidden(ORIENTATION_LANDSCAPE))
        assertTrue(immersive.reclaimsTopCutout(ORIENTATION_PORTRAIT))
        assertFalse(immersive.reclaimsTopCutout(ORIENTATION_LANDSCAPE))
        for (orientation in listOf(ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE)) {
            assertTrue(immersive.isHidden(orientation))
            assertFalse(visible.isHidden(orientation))
            assertFalse(visible.reclaimsTopCutout(orientation))
            assertFalse(outside.reclaimsTopCutout(orientation))
        }
    }

    @Suppress("DEPRECATION")
    @Test fun applyingTheEditorRequestOnlyChangesStatusBarVisibility() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window = activity.get().window
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            for (orientation in listOf(ORIENTATION_PORTRAIT, ORIENTATION_LANDSCAPE)) {
                for (request in listOf(EditorStatusBarRequest.IMMERSIVE_EDITOR, EditorStatusBarRequest.SHOW_IN_EDITOR,
                    EditorStatusBarRequest.FOLLOW_ORIENTATION)) {
                    applyDroideOrientationStatusBar(window, orientation, request)
                    val flags = window.decorView.systemUiVisibility
                    assertEquals(request.isHidden(orientation), flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
                    assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
                }
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
