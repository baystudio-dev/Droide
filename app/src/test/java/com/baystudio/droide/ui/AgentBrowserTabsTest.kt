package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h760dp-xhdpi")
class AgentBrowserTabsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = Files.createTempDirectory("agent-browser-tabs").toFile()
    private val tabs = WorkspaceBrowserTabs(AgentBrowserController(root, initialViewport = AgentBrowserViewport.Desktop), AgentBrowserController(root))
    private val caller = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val ctx = agentWorkflowContext(root, browserController = tabs.agent, browserTabs = tabs)
    private val views = mutableMapOf<AgentBrowserController, WebView>()
    private var automation by mutableStateOf(false)
    private var open by mutableStateOf(true)
    private lateinit var placement: WorkspaceBrowserPlacement

    @Before fun setup() {
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        placement = WorkspaceBrowserPlacement()
    }

    @After fun clean() { caller.cancel(); ctx.webResearch.close(); root.deleteRecursively() }

    private fun pump(until: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!until() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(2)
        }
        assertTrue(until())
    }

    private fun tool(args: String) = caller.async { AgentTools.execute("browser", Json.parseToJsonElement(args).jsonObject, ctx) }

    private fun mount() = compose.setContent {
        DroideTheme {
            Box(Modifier.size(360.dp, 640.dp).onGloballyPositioned { placement.updateRoot(it.boundsInRoot()) }) {
                if (open) Box(Modifier.fillMaxSize()) { WorkspaceBrowserPlaceholder(placement, WorkspaceBrowserSlot.MAIN) }
                WorkspaceBrowserTabHosts(placement, if (open) WorkspaceBrowserSlot.MAIN else null,
                    tabs.user, tabs.agent, automation, chatVisible = !open, userTabShared = false) { pane, shown, ready, active ->
                    WorkspaceBrowserPane("", {}, pane, presented = shown, captureReady = ready, tabActive = active) { context ->
                        WebView(context).also { check(views.put(pane, it) == null) }
                    }
                }
            }
        }
    }

    @Test fun agentResizesItsLiveViewportWhileTheUserKeepsTheirTabAndDraft() {
        mount()
        compose.onNodeWithTag("browser-url").performTextReplacement("https://example.org/draft")
        val personal = compose.runOnIdle { assertFalse(views.containsKey(tabs.agent)); views.getValue(tabs.user) }
        compose.onNodeWithTag("desktop-browser-preview").assertDoesNotExist()
        val personalSize = personal.measuredWidth to personal.measuredHeight
        assertTrue(personalSize.first > 0 && personalSize.second > 0)
        compose.runOnIdle { open = false }
        compose.runOnIdle { assertTrue(tabs.user.retention.value.attached); assertFalse(tabs.user.retention.value.resumed) }
        compose.runOnIdle { automation = true; open = true }
        compose.waitForIdle()
        val agentView = views.getValue(tabs.agent)
        fun checkSize(width: Int, height: Int) = compose.runOnIdle {
            val host = agentView.parent as BrowserViewHost
            val density = compose.activity.resources.displayMetrics.density
            assertTrue(density > 1f)
            assertEquals(density, agentView.resources.displayMetrics.density, .01f)
            assertEquals(width * density, agentView.measuredWidth.toFloat(), 1f)
            assertEquals(height * density, agentView.measuredHeight.toFloat(), 1f)
            assertEquals(agentView.scaleX, agentView.scaleY, .001f)
            assertEquals(minOf(host.width.toFloat() / agentView.measuredWidth, host.height.toFloat() / agentView.measuredHeight), agentView.scaleX, .001f)
            assertTrue(agentView.translationX >= -.5f && agentView.translationY >= -.5f)
            assertTrue(agentView.translationX + agentView.scaleX * agentView.measuredWidth <= host.width + .5f)
            assertTrue(agentView.translationY + agentView.scaleY * agentView.measuredHeight <= host.height + .5f)
        }
        checkSize(1280, 800)
        compose.onNodeWithTag("desktop-browser-preview").assertExists()
        val resized = tool("""{"operation":"viewport","mode":"mobile"}""")
        pump { resized.isCompleted }
        assertTrue(runBlocking { resized.await() }.contains("browser_viewport"))
        checkSize(390, 844)
        compose.onNodeWithTag("desktop-browser-preview").assertDoesNotExist()
        val custom = tool("""{"operation":"viewport","width":1024,"height":600}""")
        pump { custom.isCompleted }
        checkSize(1024, 600)
        compose.runOnIdle { automation = false }
        compose.onNodeWithTag("browser-url").assertTextEquals("https://example.org/draft")
        compose.runOnIdle {
            assertSame(personal, views.getValue(tabs.user))
            assertEquals(personalSize, personal.measuredWidth to personal.measuredHeight)
            assertEquals(1f, personal.scaleX, .001f)
            assertFalse(tabs.agent.retention.value.resumed)
        }
    }

    @Test fun thePrivateTabRequiresFreshHumanConsentAndCanBeReclaimedDuringAQueuedAction() {
        val session = requireNotNull(ctx.sessionId)
        assertSame(tabs.agent, tabs.route(null, session).browser)
        assertNull(tabs.route("user", session).browser)
        val peek = tool("""{"operation":"status","tab":"user"}""")
        pump { peek.isCompleted }
        assertTrue(runBlocking { peek.await() }.contains("DENIED"))
        val refused = tool("""{"operation":"request_user_tab"}""")
        pump { ctx.approvals.pending.value?.kind == "browser_user_tab" }
        ctx.approvals.resolve(ApprovalResolution.DENY)
        pump { refused.isCompleted }
        assertNull(tabs.route("user", session).browser)
        val allowed = tool("""{"operation":"request_user_tab"}""")
        pump { ctx.approvals.pending.value?.kind == "browser_user_tab" }
        ctx.approvals.resolve(ApprovalResolution.ALLOW_ONCE)
        pump { allowed.isCompleted }
        val route = tabs.route("user", session)
        assertSame(tabs.user, route.browser)
        assertNull(tabs.route("user", "other-chat").browser)
        assertSame(tabs.agent, tabs.route(null, session).browser)
        val ticket = tabs.user.registerToolWork()
        tabs.revoke()
        assertTrue(ticket.invalidated.isCompleted)
        assertNull(tabs.route("user", session).browser)
        tabs.share(session) { true }
        assertFalse(tabs.isCurrent(route))
        tabs.user.releaseToolWork(ticket)
    }

    @Test fun invalidViewportDoesNotAlterEitherTab() {
        val bad = tool("""{"operation":"viewport","mode":"desktop","width":1920,"height":1920}""")
        pump { bad.isCompleted }
        val rejected = runCatching { runBlocking { bad.await() } }
        assertTrue(rejected.isFailure || rejected.getOrThrow().contains("2304000"))
        assertEquals(AgentBrowserViewport.Desktop, tabs.agent.viewport.value)
        assertNull(tabs.user.viewport.value)
    }
}
