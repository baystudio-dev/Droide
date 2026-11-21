package com.baystudio.droide.ui

import org.robolectric.RuntimeEnvironment

import com.baystudio.droide.core.ApplicationWorkAdmission

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.baystudio.droide.core.AgentBrowserController
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Exercises the production host and pane with a controlled renderer.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowserInitialNavigationOwnershipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("browser-first-navigation-").toFile()
    private val controller = AgentBrowserController(root)
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val placement = WorkspaceBrowserPlacement()
    private val views = mutableListOf<InitialView>()
    private var selected by mutableStateOf<WorkspaceBrowserSlot?>(null)

    private class InitialView(context: Context) : WebView(context) {
        var page: String? = null
        val navigations = mutableListOf<String>()
        var domReads = 0
        var downloads: DownloadListener? = null
        override fun setDownloadListener(listener: DownloadListener?) { downloads = listener }
        var afterHistoryRead: (() -> Unit)? = null
        override fun copyBackForwardList(): WebBackForwardList = super.copyBackForwardList().also {
            afterHistoryRead?.let { callback -> afterHistoryRead = null; callback() }
        }
        override fun getUrl(): String? = page
        override fun getTitle() = "Initial fixture"
        override fun loadUrl(url: String) {
            navigations += url
            page = url
            webViewClient.onPageStarted(this, url, null)
            webViewClient.onPageFinished(this, url)
        }
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            domReads++
            resultCallback?.onReceiveValue(JsonPrimitive(buildJsonObject {
                put("ok", true); put("url", page.orEmpty())
            }.toString()).toString())
        }
    }

    @Before fun prepare() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()

        root.resolve("requested.html").writeText("<html><body>Requested</body></html>")
        root.resolve("default.html").writeText("<html><body>Default</body></html>")
        controller.bindOpenRequest { selected = WorkspaceBrowserSlot.MAIN }
    }

    @After fun cleanup() { caller.cancel(); root.deleteRecursively() }

    private fun mount() {
        compose.setContent {
            DroideTheme {
                Box(Modifier.size(360.dp, 640.dp).onGloballyPositioned { placement.updateRoot(it.boundsInRoot()) }) {
                    if (selected != null) Box(Modifier.fillMaxSize()) {
                        WorkspaceBrowserPlaceholder(placement, WorkspaceBrowserSlot.MAIN)
                    }
                    WorkspaceBrowserHost(placement, selected, keepActive = false, stopping = false) { presented, ready ->
                        WorkspaceBrowserPane(controller.htmlPreviewUrl("default.html"), {}, controller,
                            presented = presented, captureReady = ready,
                            createWebView = { InitialView(it).also(views::add) })
                    }
                }
            }
        }
    }

    private fun awaitNavigation(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.waitForIdle()
        }
        assertTrue("Browser navigation did not complete before the test deadline", condition())
    }

    @Test fun agentFirstOpenCannotBeOverwrittenByNonemptyPaneDefaultQueuedDuringMount() {
        mount()
        assertTrue(views.isEmpty())
        val requested = controller.htmlPreviewUrl("requested.html")
        val open = caller.async { controller.execute("open", url = requested, waitMs = 250) }
        awaitNavigation { open.isCompleted && controller.retention.value.activeOperations == 0 }
        compose.waitForIdle()
        assertTrue(runBlocking { open.await() }.contains(requested))
        assertEquals(1, views.size)
        assertEquals(listOf(requested), views.single().navigations)
        assertEquals(requested, controller.currentUrl())
    }

    @Test fun ordinaryHumanFirstMountStillLoadsItsDefaultExactlyOnce() {
        selected = WorkspaceBrowserSlot.MAIN
        mount()
        awaitNavigation {
            views.singleOrNull()?.navigations?.isNotEmpty() == true && controller.retention.value.activeOperations == 0
        }
        compose.waitForIdle()
        assertEquals(listOf(controller.htmlPreviewUrl("default.html")), views.single().navigations)
        assertEquals(0, views.single().domReads)
    }

    @Test fun firstMountWhileBackgroundedWaitsForForegroundAndDoesNotReloadOnReturn() {
        controller.setApplicationVisible(false)
        selected = WorkspaceBrowserSlot.MAIN
        mount()
        compose.waitForIdle()
        assertTrue(views.single().navigations.isEmpty())
        compose.onAllNodesWithText("Blocked:", substring = true).assertCountEquals(0)

        compose.runOnIdle { controller.setApplicationVisible(true) }
        awaitNavigation { views.single().navigations.size == 1 && controller.retention.value.activeOperations == 0 }
        compose.runOnIdle { controller.setApplicationVisible(false) }
        compose.waitForIdle()
        compose.runOnIdle { controller.setApplicationVisible(true) }
        compose.waitForIdle()
        assertEquals(listOf(controller.htmlPreviewUrl("default.html")), views.single().navigations)
    }

    @Test fun retainedEmptyPaneLoadsWhenItIsFirstPresented() {
        var presented by mutableStateOf(false)
        compose.setContent {
            DroideTheme {
                WorkspaceBrowserPane(controller.htmlPreviewUrl("default.html"), {}, controller, presented = presented,
                    createWebView = { InitialView(it).also(views::add) })
            }
        }
        compose.waitForIdle()
        assertTrue(views.single().navigations.isEmpty())
        compose.runOnIdle { presented = true }
        awaitNavigation { views.single().navigations.size == 1 && controller.retention.value.activeOperations == 0 }
        assertEquals(listOf(controller.htmlPreviewUrl("default.html")), views.single().navigations)
    }

    @Test fun webDownloadCanBeDismissedAndSavedFromTheNativeConfirmation() {
        val store = org.robolectric.Robolectric.setupContentProvider(
            com.baystudio.droide.core.BrowserDownloadTest.DownloadStore::class.java, "media")
        selected = WorkspaceBrowserSlot.MAIN
        mount()
        awaitNavigation { views.singleOrNull()?.navigations?.isNotEmpty() == true }
        fun offer() = compose.runOnIdle {
            requireNotNull(views.single().downloads).onDownloadStart("data:application/octet-stream;base64,AP8r",
                null, "attachment; filename=fixture.bin", "application/octet-stream", 3)
        }
        offer(); compose.onNodeWithText("Cancel").performClick()
        assertNull(controller.pendingDownload.value); assertEquals(0, store.published)
        offer(); compose.onNodeWithText("Download").performClick()
        awaitNavigation { store.published == 1 }
        assertArrayEquals(byteArrayOf(0,-1,43),store.file.readBytes())
    }

    @Test fun returningToAPagePreservesTheAddressDraftAndAllowsTheNextManualNavigation() {
        selected = WorkspaceBrowserSlot.MAIN
        mount()
        val initial = controller.htmlPreviewUrl("default.html")
        awaitNavigation { views.singleOrNull()?.navigations == listOf(initial) && controller.retention.value.activeOperations == 0 }
        val address = compose.onNodeWithTag("browser-url")
        address.performTextReplacement("https://github.com")
        compose.runOnIdle { controller.setApplicationVisible(false) }
        compose.waitForIdle()
        compose.runOnIdle { controller.setApplicationVisible(true) }
        compose.waitForIdle()
        address.assertTextEquals("https://github.com")
        assertEquals(listOf(initial), views.single().navigations)

        val requested = controller.htmlPreviewUrl("requested.html")
        address.performTextReplacement(requested)
        address.performImeAction()
        awaitNavigation { controller.currentUrl() == requested && controller.retention.value.activeOperations == 0 }
        assertEquals(listOf(initial, requested), views.single().navigations)
    }

    @Test fun interruptionBeforeNativeLoadLeavesTheEmptyViewEligibleForFirstNavigation() {
        lateinit var binding: AgentBrowserController.Binding
        compose.setContent {
            AndroidView(factory = { context ->
                InitialView(context).also { view ->
                    views += view
                    controller.configure(view)
                    binding = controller.attach(view)
                    controller.setSurfacePresented(binding, view, true)
                }
            }, modifier = Modifier.fillMaxSize(), onRelease = { view ->
                controller.detach(binding, view, preserveState = false)
                view.destroy()
            })
        }
        compose.waitForIdle()
        val view = views.single()
        val url = controller.htmlPreviewUrl("default.html")
        view.afterHistoryRead = { Handler(Looper.getMainLooper()).post { controller.setApplicationVisible(false) } }
        val first = caller.async { runCatching { controller.initializeDefaultPage(binding, url) } }
        awaitNavigation { first.isCompleted }
        assertTrue(runBlocking { first.await() }.isFailure)
        assertTrue(view.navigations.isEmpty())

        compose.runOnIdle { controller.setApplicationVisible(true) }
        val second = caller.async { controller.initializeDefaultPage(binding, url) }
        awaitNavigation { second.isCompleted }
        runBlocking { second.await() }
        assertEquals(listOf(url), view.navigations)
    }
}
