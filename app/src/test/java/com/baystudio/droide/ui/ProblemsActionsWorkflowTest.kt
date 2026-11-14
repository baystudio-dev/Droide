package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProblemsActionsWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("problems-actions-").toFile()
    private val replies = mutableListOf<CompletableDeferred<List<LspCodeAction>>>()
    private val resolution = CompletableDeferred<LspCodeAction>()
    private var resolutions = 0
    private var applied = 0
    private var editorMode = false
    private val bridge = ActiveEditorBridge()
    private val focus = AccessoryInputFocusController()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val terminals = TerminalManager(scope, root)
    private val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow(mapOf(root.resolve("a.kt").toURI().toString() to
            listOf(LspDiagnostic(1, 1, 1, 2, 1, "Repair this line", "fixture"))))
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        override suspend fun codeActionsAt(path: String, line: Int, col: Int): List<LspCodeAction> =
            codeActions(path, diagnostics.value.values.first().first())
        override suspend fun codeActions(path: String, diagnostic: LspDiagnostic): List<LspCodeAction> {
            val reply = CompletableDeferred<List<LspCodeAction>>().also(replies::add)
            return withContext(NonCancellable) { reply.await() }
        }
        override suspend fun resolveCodeAction(path: String, action: LspCodeAction): LspCodeAction {
            resolutions++
            return withContext(NonCancellable) { resolution.await() }
        }
    }

    @After fun cleanup() {
        replies.forEach { it.complete(emptyList()) }
        resolution.complete(action("unused"))
        terminals.dispose(false)
        scope.cancel()
        root.deleteRecursively()
    }

    private fun action(title: String) = LspCodeAction(title, edit = LspWorkspaceEdit(emptyList()))
    private fun mount(editor: Boolean = false) {
        editorMode = editor
        root.resolve("a.kt").writeText("keep this content")
        val files = FileRepository(root)
        val document = EditorWorkspaceState().document("a.kt")
        if (editor) runBlocking { document.ensureLoaded(files) }
        compose.setContent { DroideTheme {
            if (editor) EditorPane(files, "a.kt", document, bridge, lsp = lsp,
                formatter = FormatterManager(root, terminals), theme = ThemeManager(root).snapshot(),
                accessoryInputFocus = focus, accessoryKeysExpanded = false, onAccessoryKeysExpandedChange = {},
                onApplyWorkspaceEdit = { applied++ })
            else ProblemsSheet(files, lsp, onOpen = { _, _, _ -> }, onApplyWorkspaceEdit = { applied++ })
        } }
    }
    private fun open() {
        if (editorMode) compose.runOnIdle { assertTrue(bridge.requestQuickFix()) }
        else compose.onNodeWithContentDescription("Quick Fix").performClick()
    }
    private fun close() = compose.onNodeWithText("Close").performClick()

    @Test fun dismissedQueryCannotEraseActionsFromTheNextRequest() {
        mount(); open()
        compose.waitUntil(5_000) { replies.size == 1 }
        close(); open()
        compose.waitUntil(5_000) { replies.size == 2 }
        compose.runOnIdle { replies[1].complete(listOf(action("Current action"))) }
        compose.onNodeWithText("Current action").assertExists()
        compose.runOnIdle { replies[0].complete(listOf(action("Stale action"))) }
        compose.onNodeWithText("Stale action").assertDoesNotExist()
        compose.onNodeWithText("Current action").assertExists()
    }

    @Test fun closingAResolvingActionPreventsLateApplication() = dismissedResolution(false)
    @Test fun closingEditorQuickFixPreventsLateApplication() = dismissedResolution(true)
    private fun dismissedResolution(editor: Boolean) {
        mount(editor); open()
        compose.waitUntil(5_000) { replies.size == 1 }
        compose.runOnIdle { replies[0].complete(listOf(action("Fix line"))) }
        compose.onNodeWithText("Fix line").performClick()
        compose.waitUntil(5_000) { resolutions == 1 }
        close()
        compose.runOnIdle { resolution.complete(action("Fix line")) }
        compose.waitForIdle()
        assertEquals(0, applied)
        assertEquals("keep this content", root.resolve("a.kt").readText())
    }

    @Test fun identicalActionTitlesRenderAndARepeatedTapDoesNotApplyTwice() = repeatedAction(false)
    @Test fun editorSupportsDuplicateActionTitlesAndIgnoresRepeatedTap() = repeatedAction(true)
    private fun repeatedAction(editor: Boolean) {
        mount(editor); open()
        compose.waitUntil(5_000) { replies.size == 1 }
        compose.runOnIdle { replies[0].complete(listOf(action("Fix line"), action("Fix line"))) }
        compose.onAllNodesWithText("Fix line").assertCountEquals(2)
        compose.onAllNodesWithText("Fix line")[0].performClick()
        compose.waitUntil(5_000) { resolutions == 1 }
        compose.onAllNodesWithText("Fix line")[0].performTouchInput { click() }
        assertEquals(1, resolutions)
        compose.runOnIdle { resolution.complete(action("Fix line")) }
        compose.waitForIdle()
        assertEquals(1, applied)
    }
}
