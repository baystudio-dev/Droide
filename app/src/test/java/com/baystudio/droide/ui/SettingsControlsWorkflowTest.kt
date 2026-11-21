package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess

/** Actual settings navigation and scroll actions; no tool install or authenticated service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w900dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsControlsWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("settings-motion-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = RuntimeEnvironment.getApplication()
    private val terminals by lazy { TerminalManager(scope, root) }
    private val bridge by lazy { DeviceBridgeManager(app, scope) }
    private val development by lazy { AndroidDevelopmentManager(app, root, bridge) }
    private val size = mutableStateOf(900.dp to 640.dp)
    private val wrap = mutableStateOf(false)
    private var closes = 0
    private lateinit var renderingView: android.view.View
    private val opened = mutableListOf<String>()
    private val mounted = mutableStateOf(true)

    // Android's journal requires a positive app PID; Robolectric Application defaults to zero.
    // No operation is started and no real process is signaled by these settings-only tests.
    @Before fun prepareProcessIdentity() { ShadowProcess.setPid(4242) }

    @After fun cleanup() { terminals.dispose(false); bridge.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }

    private fun mount(
        themeAction: suspend (String) -> Unit = { ThemeManager(root).select(it) },
        fontScale: Float = 1f,
        permissionAction: suspend (String, PermEffect) -> PermissionPolicyDocument = { _, _ -> PermissionPolicyDocument.EMPTY },
        category: String? = null,
        launchContext: android.content.Context? = null,
        wordwrapAction: suspend (Boolean) -> Unit = { wrap.value = it },
        retryAction: suspend (String) -> String = { "Retry checked" },
        configAction: suspend () -> Unit = {},
        health: List<McpHealthSnapshot> = emptyList(),
    ) {
        val themes = ThemeManager(root)
        val formatter = FormatterManager(root, terminals)
        val github = GitHubAccountManager(app)
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalContext provides (launchContext ?: androidx.compose.ui.platform.LocalContext.current),
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale),
        ) { DroideTheme { Box(Modifier.size(size.value.first, size.value.second)) {
            renderingView = androidx.compose.ui.platform.LocalView.current
            val context = app
            val workbench = remember(context) { WorkbenchPreferences.flow(context) }.collectAsState(WorkbenchPreferences.Snapshot())
            if (mounted.value) SettingsScreen(themes, formatter, wrap.value, false, true, CodingKeyboardMode.COMPACT,
                workbench.value.codeStyleDefaults, development, bridge, github, "Motion project", initialPermissionPolicy = PermissionPolicyDocument.EMPTY,
                showEditorStatusBar = workbench.value.showEditorStatusBar,
                onShowEditorStatusBar = { WorkbenchPreferences.setShowEditorStatusBar(context, it) },
                onWordwrap = wordwrapAction, onHardwareShortcuts = {}, onAccessoryKeysComfortable = {},
                onCodingKeyboardMode = {}, onDetectIndentation = { WorkbenchPreferences.setDetectIndentation(context, it) },
                onIndentStyle = { WorkbenchPreferences.setIndentStyle(context, it) }, onTabWidth = { WorkbenchPreferences.setTabWidth(context, it) },
                onIndentSize = { WorkbenchPreferences.setIndentSize(context, it) }, onContinuationIndent = { WorkbenchPreferences.setContinuationIndent(context, it) }, onTheme = themeAction, onPermissionRuleChange = permissionAction,
                onOpenProviders = { opened.add("providers") }, onOpenModels = { opened.add("models") }, onOpenExtensions = { opened.add("extensions") }, onOpenFiles = { opened.add("files") },
                onOpenTerminal = { opened.add("terminal") }, onOpenGit = { opened.add("git") }, onOpenRunDebug = { opened.add("run") }, onOpenAgent = { opened.add("agent") },
                onOpenAgentSessions = { opened.add("sessions") }, onOpenCapabilityHealth = { opened.add("health") }, mcpHealth = health,
                onRetryMcp = retryAction, onOpenMcpConfig = configAction, initialCategoryName = category, onDismiss = { closes++; mounted.value = false })
        } } } }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port-mdpi")
    fun phoneDirectoryAndEditorPreviewFollowTheSameNavigation() {
        size.value = 390.dp to 844.dp
        mount()
        compose.onNodeWithText("Workbench").assertIsDisplayed()
        compose.onNodeWithText("Development").assertIsDisplayed()
        screenshot("phone-directory")
        compose.onNodeWithText("Editor").performClick()
        compose.onNodeWithContentDescription("Kotlin code preview, main.kt").assertIsDisplayed()
        screenshot("phone-editor")
        compose.onNodeWithText("Indent size").performScrollTo().performClick()
        compose.onNode(hasText("8") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil { runBlocking { WorkbenchPreferences.flow(app).first().indentSize == 8 } }
        compose.onNodeWithText("Tabs").performScrollTo().performClick()
        compose.waitUntil { runBlocking { WorkbenchPreferences.flow(app).first().indentStyle == IndentStyle.TABS } }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search settings").assertIsDisplayed()
    }

    @Test fun workspaceThemeCanBePreviewedSavedAndReopened() {
        mount(category = "APPEARANCE")
        compose.onNodeWithText("Workspace", useUnmergedTree = true).performClick()
        compose.waitUntil { compose.onAllNodesWithText("Droide Dark").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Droide Dark").assertIsSelected()
        screenshot("tablet-appearance")
        compose.onNodeWithText("Tokyo Night").performScrollTo().performClick()
        compose.waitUntil { ThemeManager(root).current().name == "tokyonight" }
        compose.onNodeWithText("Tokyo Night").assertIsSelected()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        compose.onNodeWithText("Workspace", useUnmergedTree = true).performClick()
        compose.waitUntil { compose.onAllNodesWithText("Tokyo Night").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tokyo Night").assertIsSelected()
    }

    @Test fun rotationKeepsTheSelectedPageAndWorkspaceScope() {
        size.value = 360.dp to 640.dp
        mount()
        compose.onNodeWithText("Workspace", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Editor").performClick()
        compose.onNodeWithText("External formatter fallback").assertIsDisplayed()
        compose.runOnIdle { size.value = 850.dp to 360.dp }
        compose.onNodeWithText("Search settings").assertIsDisplayed()
        compose.onNodeWithText("External formatter fallback").assertIsDisplayed()
        screenshot("phone-landscape")
        compose.runOnIdle { size.value = 360.dp to 640.dp }
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithText("External formatter fallback").assertIsDisplayed()
    }

    @Test fun activeSearchSurvivesSwitchingFromTwoPanesToOne() {
        mount(category = "GENERAL")
        compose.onNodeWithText("Search settings").performTextInput("continuation indent")
        compose.runOnIdle { size.value = 360.dp to 640.dp }
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Search settings").assertIsDisplayed().performTextInput("continuation indent")
        compose.onNodeWithText("Editor").assertIsDisplayed().performClick()
        compose.onNodeWithText("Continuation indent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Search settings").assertIsDisplayed()
    }

    @Test fun smallWindowAndLargeTextKeepActionsReachable() {
        size.value = 320.dp to 285.dp
        mount(fontScale = 1.65f)
        compose.onNodeWithText("Search settings").performScrollTo().performTextInput("indent")
        compose.onNodeWithText("Editor").performScrollTo().performClick()
        compose.onNodeWithText("Continuation indent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close settings").assertIsDisplayed().performClick()
        assertEquals(1, closes)
    }

    @Test fun readOnlyFactsHaveNoTapAction() {
        mount(category = "GENERAL")
        compose.onNodeWithText("Project").assertHasNoClickAction()
        compose.onNodeWithText("Motion project").assertHasNoClickAction()
    }

    @Test fun wrapExposesSwitchStateAndWritesPreference() {
        mount(category = "EDITOR")
        compose.onNodeWithText("Word wrap").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(true, wrap.value) }
    }

    @Test
    @Config(qualifiers = "in-rID-w360dp-h640dp-port-mdpi")
    fun compactEditorShowsTheIndonesianStatusBarSwitch() {
        size.value = 360.dp to 640.dp
        mount(category = "EDITOR")
        compose.onNodeWithText("Tampilkan status bar Android").performScrollTo().assertIsOff()
    }

    @Test fun androidStatusBarSwitchPersistsAcrossReopeningAndIsUserScoped() {
        val context = app
        fun stored() = runBlocking { WorkbenchPreferences.flow(context).first().showEditorStatusBar }
        assertEquals(false, stored())
        mount(category = "EDITOR")
        val row = compose.onNodeWithText("Show Android status bar")
        row.performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { stored() }
        row.assertIsOn()
        compose.onNodeWithText("Workspace", useUnmergedTree = true).performClick()
        row.assertDoesNotExist()
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        compose.runOnIdle { mounted.value = true }
        row.performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { !stored() }
        row.assertIsOff()
    }

    @Test fun searchHasAnExplicitEmptyState() {
        mount()
        compose.onNodeWithText("Search settings").performTextInput("no-such-setting-9137")
        compose.onNodeWithText("No matching settings").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = System.getProperty("droide.headerEvidenceDir") ?: return
        compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(minOf(renderingView.width, (size.value.first.value * renderingView.resources.displayMetrics.density).toInt()), minOf(renderingView.height, (size.value.second.value * renderingView.resources.displayMetrics.density).toInt()), android.graphics.Bitmap.Config.ARGB_8888)
            renderingView.draw(android.graphics.Canvas(bitmap))
            val file = java.io.File(directory, "$name.png").apply { parentFile.mkdirs() }
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun agentLinksDispatchTheirRealDestinationCallbacks() {
        mount(category = "AI")
        compose.onNodeWithText("Open Agent").performScrollTo().performClick()
        compose.onNodeWithText("Agent sessions").performScrollTo().performClick()
        compose.onNodeWithText("Choose model").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("agent", "sessions", "models"), opened) }
    }

    @Test fun fileNavigationAndReadOnlyOverviewAreDistinct() {
        size.value = 360.dp to 640.dp
        mount(category = "FILES")
        compose.onNodeWithText("Active workspace").assertHasNoClickAction()
        compose.onNodeWithText("Open Files workbench").performScrollTo().assertHasClickAction()
        screenshot("settings-files-compact")
        compose.onNodeWithText("Open Files workbench").performClick()
        compose.runOnIdle { assertEquals(listOf("files"), opened) }
    }

    @Test fun compactEditorControlsRenderAndRemainUsable() {
        size.value = 360.dp to 640.dp
        mount(category = "EDITOR")
        compose.onNodeWithText("Word wrap").assertIsDisplayed().performClick().assertIsOn()
        screenshot("settings-editor-compact")
        compose.onNodeWithText("Continuation indent").performScrollTo().assertIsDisplayed()
    }

    @Test fun emptySearchCanBeClearedWithoutLosingNavigation() {
        size.value = 360.dp to 640.dp
        mount()
        compose.onNodeWithText("Search settings").performTextInput("no-such-setting-9137")
        screenshot("settings-empty-search")
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Editor").performClick()
        compose.onNodeWithText("Word wrap").assertIsDisplayed()
    }

    @Test fun workspaceFormatterSavesRealConfigurationAndReadsBack() {
        mount(category = "EDITOR")
        compose.onNodeWithText("Workspace", useUnmergedTree = true).performClick()
        compose.onNodeWithText("External formatter fallback").assertIsOff().performClick()
        compose.waitUntil { java.io.File(root, ".droide/formatter.json").exists() }
        compose.waitUntil { FormatterManager(root, terminals).isEnabled() }
        compose.onNodeWithText("External formatter fallback").assertIsOn()
        compose.runOnIdle { assertEquals(true, FormatterManager(root, terminals).isEnabled()) }
    }
}
