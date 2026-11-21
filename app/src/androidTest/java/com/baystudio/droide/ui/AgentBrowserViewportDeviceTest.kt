package com.baystudio.droide.ui

import android.graphics.Color
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import java.nio.ByteBuffer
import java.security.MessageDigest
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.baystudio.droide.MainActivity
import com.baystudio.droide.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentBrowserViewportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun desktopLayoutAndFarCornerSurviveAPhoneSizedPreviewAndMobileRoundTrip() = runBlocking {
        val activity = compose.activity
        val controller = AgentBrowserController(activity.cacheDir, initialViewport = AgentBrowserViewport.Desktop)
        lateinit var browser: WebView
        compose.runOnUiThread { activity.setContent {
            DroideTheme { Box(Modifier.size(320.dp, 460.dp)) {
                WorkspaceBrowserPane("", {}, controller) { ctx -> WebView(ctx).also { browser = it } }
            } }
        } }
        compose.waitForIdle()
        val document = "https://viewport.example.org/local-fixture"
        val html = """<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>*{margin:0}html{background:white}#stripe{position:fixed;top:25vh;left:0;width:25vw;height:50vh;background:blue;pointer-events:none}#corner{position:fixed;right:0;bottom:0;width:24px;height:24px;background:red}
            @media(min-width:1000px){#corner{background:lime}}</style><div id="stripe"></div><div id="corner"></div><input id="draft"><script>
            window.presses = 0; addEventListener('pointerdown', () => window.presses++);
            document.getElementById('corner').onclick = () => { window.hitCorner = true; };
            </script>"""
        compose.runOnUiThread { browser.loadDataWithBaseURL(document, html, "text/html", "UTF-8", null) }
        suspend fun evaluate(js: String): String {
            val result = CompletableDeferred<String>()
            compose.runOnUiThread { browser.evaluateJavascript(js) { result.complete(it) } }
            return withTimeout(5_000) { result.await() }
        }
        suspend fun assertViewport(profile: AgentBrowserViewport, color: Int): ByteArray {
            withTimeout(8_000) {
                while (evaluate("!!document.getElementById('corner')") != "true" ||
                    BrowserCssViewport.parse(evaluate(BrowserCssViewport.PROBE))?.matches(profile) != true) delay(32)
            }
            return AgentBrowserCapture.withCapture(browser, false, document, checkLease = {}, scaleOnMain = { null },
                cssViewport = profile) { frame ->
                assertEquals(profile.width, frame.bitmap.width)
                assertEquals(profile.height, frame.bitmap.height)
                assertEquals(color, frame.bitmap.getPixel(frame.bitmap.width - 6, frame.bitmap.height - 6))
                val pixels = ByteBuffer.allocate(frame.bitmap.byteCount)
                frame.bitmap.copyPixelsToBuffer(pixels)
                MessageDigest.getInstance("SHA-256").digest(pixels.array())
            }
        }
        assertViewport(AgentBrowserViewport.Desktop, Color.GREEN)
        assertEquals("false", evaluate("/Android| Mobile /.test(navigator.userAgent)"))
        evaluate("document.getElementById('draft').value='preserved'")
        val pageScale = evaluate("visualViewport.scale")
        val current = assertViewport(AgentBrowserViewport.Desktop, Color.GREEN)
        val preview = compose.onNodeWithTag("desktop-browser-preview")
        fun previewPixel(): Int {
            val image = preview.captureToImage().toPixelMap()
            return image[(image.width * .12f).toInt(), image.height / 2].toArgb()
        }
        assertEquals(Color.BLUE, previewPixel())
        preview.performTouchInput { doubleClick() }
        assertEquals(Color.WHITE, previewPixel())
        preview.performTouchInput { swipe(center, center + Offset(width * .3f, 0f)) }
        assertEquals(Color.BLUE, previewPixel())
        assertArrayEquals(current, assertViewport(AgentBrowserViewport.Desktop, Color.GREEN))
        assertEquals("0", evaluate("window.presses + scrollX + scrollY"))
        assertEquals(pageScale, evaluate("visualViewport.scale"))
        val corner = AgentBrowserNativeInput.Point(.99f, .99f)
        AgentBrowserNativeInput.gesture(browser, corner, dispatch = { withContext(Dispatchers.Main.immediate) { it() } },
            cancel = { withContext(Dispatchers.Main.immediate) { it() } })
        withTimeout(3_000) { while (evaluate("window.hitCorner === true") != "true") delay(16) }
        controller.changeViewport(AgentBrowserViewport.Mobile) { true }
        assertViewport(AgentBrowserViewport.Mobile, Color.RED)
        preview.assertDoesNotExist()
        controller.changeViewport(AgentBrowserViewport.Desktop) { true }
        assertViewport(AgentBrowserViewport.Desktop, Color.GREEN)
        assertEquals("\"preserved\"", evaluate("document.getElementById('draft').value"))
    }
}
