package com.baystudio.droide.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import com.baystudio.droide.core.AgentBrowserViewport
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class DesktopBrowserPreviewTest {
    private val preview = DesktopBrowserPreview(AgentBrowserViewport.Desktop)
    private val pane = Size(400f, 800f)

    private fun checkOffset(x: Float, y: Float, offset: Offset) {
        assertEquals(x, offset.x, .01f)
        assertEquals(y, offset.y, .01f)
    }

    @Test fun pinchPreservesThePointBelowTheFingersAndFollowsTheirMovement() {
        val point = Offset(150f, 400f)
        preview.transform(pane, point, Offset(10f, 0f), 2f)
        checkOffset(60f, 0f, preview.translation(pane))
        val projectedPoint = pane.center + (point - pane.center) * preview.zoom + preview.translation(pane)
        checkOffset(160f, 400f, projectedPoint)
    }

    @Test fun letterboxingAndPanBoundsUseTheVisiblePageInBothOrientations() {
        preview.transform(pane, Offset(150f, 12f), Offset.Zero, 2f)
        checkOffset(50f, 0f, preview.translation(pane))
        preview.transform(pane, pane.center, Offset(-9999f, 9999f), 4f)
        checkOffset(-1400f, 600f, preview.translation(pane))
        val landscape = Size(800f, 400f)
        checkOffset(-2160f, 960f, preview.translation(landscape))
        preview.transform(landscape, landscape.center, Offset(9999f, -9999f), 1f)
        checkOffset(2160f, -1400f, preview.translation(landscape))
    }

    @Test fun menuFitAndDirectInputResetTheCameraAndInvalidSizesCannotCorruptIt() {
        preview.transform(pane, pane.center, Offset.Zero, 40f)
        assertEquals(8f, preview.zoom, 0f)
        preview.transform(Size.Zero, Offset.Zero, Offset.Zero, 2f)
        preview.transform(pane, pane.center, Offset.Zero, Float.NaN)
        assertEquals(8f, preview.zoom, 0f)
        preview.transform(pane, pane.center, Offset(220f, 110f), .0001f)
        assertEquals(1f, preview.zoom, 0f)
        checkOffset(0f, 0f, preview.translation(pane))
        preview.transform(pane, pane.center, Offset(40f, 20f), 4f)
        preview.switchInput()
        assertTrue(preview.directInput)
        preview.transform(pane, pane.center, Offset.Zero, 3f)
        assertEquals(1f, preview.zoom, 0f)
        preview.switchInput()
        preview.transform(pane, pane.center, Offset.Zero, 3f)
        preview.fit()
        checkOffset(0f, 0f, preview.translation(pane))
        assertEquals(1f, preview.zoom, 0f)
    }
}
