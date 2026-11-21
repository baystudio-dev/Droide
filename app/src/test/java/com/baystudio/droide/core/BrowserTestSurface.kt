package com.baystudio.droide.core

import android.app.Activity
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers

internal fun mountBrowserTestSurface(view: WebView): ActivityController<Activity> {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
    val host = FrameLayout(activity.get())
    view.isFocusableInTouchMode = true
    host.addView(view, FrameLayout.LayoutParams(120, 100))
    activity.get().setContentView(host)
    ReflectionHelpers.callInstanceMethod<Any?>(activity.get().window.decorView.parent, "handleAppVisibility",
        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
    shadowOf(Looper.getMainLooper()).idle()
    val width = View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY)
    val height = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
    host.measure(width, height)
    host.layout(0, 0, 120, 100)
    view.forceLayout()
    view.measure(width, height)
    view.layout(0, 0, 120, 100)
    // ShadowWebView does not update the View bounds from Chromium layout.
    view.left = 0; view.top = 0; view.right = 120; view.bottom = 100
    return activity
}
