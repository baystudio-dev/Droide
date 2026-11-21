package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesktopBrowserPreviewInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("browser-preview").toFile()
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = AgentBrowserController(root, initialViewport = AgentBrowserViewport.Desktop)
    private lateinit var page: Page
    private var presented by mutableStateOf(true)
    private val preview get() = compose.onNodeWithTag("desktop-browser-preview")

    private class Page(context: Context) : WebView(context) {
        val touches = mutableListOf<Pair<Int, Offset>>()
        override fun onTouchEvent(event: MotionEvent): Boolean {
            touches += event.actionMasked to Offset(event.x, event.y)
            return true
        }
        override fun getUrl() = "https://example.org/local-preview"
        override fun postVisualStateCallback(requestId: Long, callback: VisualStateCallback) = callback.onComplete(requestId)
        override fun onDraw(canvas: Canvas) = paintPage(canvas)
        override fun draw(canvas: Canvas) = paintPage(canvas)
        private fun paintPage(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLUE }
            canvas.drawRect(0f, 0f, width / 4f, height.toFloat(), paint)
            paint.color = Color.GREEN
            canvas.drawRect(width * .75f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    @Before fun setup() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        compose.setContent {
            DroideTheme { Box(Modifier.size(320.dp, 620.dp)) {
                WorkspaceBrowserPane("", {}, controller, presented = presented) { Page(it).also { page = it } }
            } }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            // ShadowWebView leaves its frame empty after the host measures the Chromium surface.
            page.right = page.measuredWidth
            page.bottom = page.measuredHeight
        }
    }

    @After fun cleanup() { caller.cancel(); root.deleteRecursively() }

    private fun <T> finish(work: suspend () -> T): T {
        val job = caller.async { work() }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            job.isCompleted
        }
        return runBlocking { job.await() }
    }

    private fun capture() = finish {
        AgentBrowserCapture.withCapture(page, false, page.url, {}, { null }, cssViewport = AgentBrowserViewport.Desktop) {
            val image = it.bitmap
            assertEquals(1280, image.width)
            assertEquals(800, image.height)
            assertEquals(Color.BLUE, image.getPixel(0, 0))
            assertEquals(Color.GREEN, image.getPixel(1279, 799))
            val pixels = ByteBuffer.allocate(image.byteCount)
            image.copyPixelsToBuffer(pixels)
            MessageDigest.getInstance("SHA-256").digest(pixels.array())
        }
    }

    private fun percent() = preview.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .removeSuffix("%").toInt()

    private fun visiblePixel(): Int = compose.runOnIdle {
        val host = page.parent as BrowserViewHost
        val image = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
        try {
            // The host applies the Android child matrix. ShadowWebView has no Chromium compositor.
            page.draw(Canvas(image).apply { concat(page.matrix) })
            image.getPixel((host.width * .12f).toInt(), host.height / 2)
        } finally { image.recycle() }
    }

    @Test fun pinchFromLetterboxAndPanDuringAgentInputKeepTheSameFullCaptureAndCoordinates() {
        val before = capture()
        assertEquals("Left stripe at fit", Color.BLUE, visiblePixel())
        val rawSize = page.width to page.height
        val siteIdentity = page.settings.userAgentString
        preview.performTouchInput {
            down(0, center - Offset(0f, height * .23f))
            down(1, center + Offset(0f, height * .23f))
            for (step in 1..8) {
                val spread = height * (.23f + step * .03f)
                updatePointerTo(0, center - Offset(0f, spread))
                updatePointerTo(1, center + Offset(0f, spread))
                move()
            }
        }
        assertTrue(page.touches.isEmpty())
        val target = AgentBrowserNativeInput.Point(.94f, .12f)
        finish {
            AgentBrowserNativeInput.gesture(page, target, durationMs = 16,
                dispatch = { withContext(Dispatchers.Main.immediate) { it() } },
                cancel = { withContext(Dispatchers.Main.immediate) { it() } })
        }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), page.touches.map { it.first })
        page.touches.forEach { (_, point) ->
            assertEquals(page.width * .94f, point.x, 1f)
            assertEquals(page.height * .12f, point.y, 1f)
        }
        preview.performTouchInput { up(1); up(0) }
        assertTrue("The letterbox pinch must magnify the preview", percent() >= 180)
        assertEquals("Zoom brings the central region into view", Color.WHITE, visiblePixel())
        preview.performTouchInput { swipe(center, center + Offset(width * .3f, 0f)) }
        assertEquals("Panning exposes the left stripe", Color.BLUE, visiblePixel())
        assertEquals(2, page.touches.size)
        assertEquals(rawSize, page.width to page.height)
        assertEquals(siteIdentity, page.settings.userAgentString)
        assertEquals(0, page.scrollX)
        assertEquals(0, page.scrollY)
        assertArrayEquals(before, capture())
        preview.performTouchInput { doubleClick() }
        assertEquals(100, percent())
    }

    @Test fun visibleMenuCanZoomFitAndExplicitlyReturnTouchToTheSite() {
        fun menu() = compose.onNodeWithContentDescription("Browser actions").performClick()
        menu()
        compose.onNodeWithContentDescription("Zoom in on preview").performClick()
        compose.onNodeWithContentDescription("Zoom in on preview").performClick()
        compose.onNodeWithContentDescription("Zoom out of preview").performClick()
        assertEquals(200, percent())
        compose.onNodeWithText("Fit to screen").performClick()
        assertEquals(100, percent())
        menu()
        compose.onNodeWithText("Interact with page").performClick()
        preview.assertDoesNotExist()
        compose.onNodeWithTag("browser-viewport").performTouchInput { click() }
        assertTrue(page.touches.any { it.first == MotionEvent.ACTION_DOWN })
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, (page.parent as View).importantForAccessibility)
        menu()
        compose.onNodeWithText("Return to preview").performClick()
        val sent = page.touches.size
        preview.performTouchInput { click() }
        assertEquals(sent, page.touches.size)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, (page.parent as View).importantForAccessibility)
        menu()
        compose.onNodeWithText("Interact with page").performClick()
        compose.runOnIdle { presented = false }
        compose.waitForIdle()
        compose.runOnIdle { presented = true }
        assertEquals(100, percent())
    }
}
