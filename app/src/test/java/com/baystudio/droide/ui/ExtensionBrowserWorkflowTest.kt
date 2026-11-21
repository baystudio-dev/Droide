package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowProcess
import org.robolectric.shadows.ShadowVMRuntime
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtensionBrowserWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val root = Files.createTempDirectory("extension-navigation-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bridge by lazy { DeviceBridgeManager(app, scope, DeviceBridgeDependencies(
        {}, { error("Unexpected device connection") }, { _, _, _ -> error("Unexpected pairing") }, { error("Unexpected discovery") })) }
    private val development by lazy { AndroidDevelopmentManager(app, root, bridge) }
    private val capabilities by lazy { UniversalCapabilityRegistry("fixture", root) }
    private val manager by lazy { DevelopmentExtensionsManager(app, root, development, bridge, capabilities) }
    private val substrate = LocalExecutionSubstrate::class.java
    private val previousRoot = ReflectionHelpers.getStaticField<File?>(substrate, "filesDir")
    private val previousContext = ReflectionHelpers.getStaticField<Context?>(substrate, "appContext")
    private val previousState = LocalExecutionSubstrate.state.value
    private var exits = 0
    private lateinit var view: View
    private lateinit var dispatcher: OnBackPressedDispatcher

    @Before fun device() {
        ShadowProcess.setPid(4242)
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a"))
        ShadowVMRuntime.setIs64Bit(true)
        ReflectionHelpers.setStaticField(substrate, "filesDir", null)
        LocalExecutionSubstrate.init(app)
    }

    @After fun release() {
        try {
            manager.close()
            capabilities.close()
            bridge.close()
            scope.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        } finally {
            ReflectionHelpers.setStaticField(substrate, "filesDir", previousRoot)
            ReflectionHelpers.setStaticField(substrate, "appContext", previousContext)
            ReflectionHelpers.getStaticField<MutableStateFlow<SubstrateState>>(substrate, "_state").value = previousState
        }
    }

    private fun mount(initialFamily: String? = null) {
        compose.setContent { DroideTheme {
            view = LocalView.current
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            Surface { Column(Modifier.fillMaxSize()) {
                val panelWidth = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 300.dp else 390.dp
                Box(Modifier.weight(1f).widthIn(max = panelWidth)) {
                    var request by remember { mutableStateOf(initialFamily) }
                    ExtensionsPane(manager, development, bridge, "main.py", initialFamilyId = request,
                        onExit = { exits++ }, onInitialFamilyHandled = { request = null })
                }
                DroideCompactNavigation(6) {}
            } }
        } }
        compose.waitUntil(20_000) { manager.snapshot.value.message != "Checking extensions…" }
    }

    private fun back() = compose.runOnIdle { dispatcher.onBackPressed() }
    private fun tapBack() = compose.onNodeWithContentDescription("Back").performClick()
    @OptIn(ExperimentalComposeUiApi::class)
    private fun chooseVersion(open: String, title: String, version: String, action: String) {
        fun frame() {
            compose.mainClock.advanceTimeBy(80)
            ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(80))
        }
        fun nodes(): List<SemanticsNode> {
            fun roots(view: View): List<ViewRootForTest> = when (view) {
                is ViewRootForTest -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
                else -> emptyList()
            }
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            return WindowInspector.getGlobalWindowViews().flatMap(::roots).flatMap { flatten(it.semanticsOwner.rootSemanticsNode) }
        }
        fun withText(node: SemanticsNode, value: String) =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true

        compose.onNodeWithText(open).assertIsDisplayed().performClick()
        frame()
        compose.runOnUiThread {
            val tree = nodes()
            val heading = tree.single { withText(it, title) }
            assertFalse("Version dialog must be visible", heading.boundsInWindow.isEmpty)
            val edit = tree.single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(edit.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(version)))
        }
        frame()
        compose.runOnUiThread {
            val tree = nodes()
            assertTrue(tree.any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == version })
            val button = tree.single { withText(it, action) && it.config.contains(SemanticsActions.OnClick) }
            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
        }
        frame()
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    private fun capture(name: String) {
        val dir = System.getProperty("droide.headerEvidenceDir") ?: return
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(dir, name + ".png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun browseSearchReviewAndHardwareBackReturnToTheSameResult() {
        mount()
        compose.onNodeWithText("Run code").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Python")
        compose.onNodeWithText("Python Runtime").assertIsDisplayed().performClick()
        val offer = UbuntuAptCatalog.VERSION
        chooseVersion("Choose version", "Python Runtime version", offer, "Continue")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Install Python Runtime").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Version " + offer).assertIsDisplayed()
        assertFalse(manager.installer.state.value.running)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Review installation").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Install Python Runtime").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Will install:").assertExists()
        assertFalse(manager.installer.state.value.running)
        assertTrue(manager.registry.list().isEmpty())
        compose.onNodeWithText("Close").performClick()
        back()
        compose.onNode(hasSetTextAction()).assertTextContains("Python")
        compose.onNodeWithText("Python Runtime").assertIsDisplayed()
        tapBack()
        compose.onNodeWithText("Run code").assertIsDisplayed()
        back()
        compose.onNodeWithText("Browse by task").assertIsDisplayed()
        tapBack()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun confirmEnqueuesExactlyOneInstallAndBackKeepsItsProgress() {
        mount("runtime.python")
        compose.onNodeWithText("Review installation").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Install Python Runtime").fetchSemanticsNodes().isNotEmpty() }
        val acquired = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val reservation = scope.launch(Dispatchers.IO) {
            manager.installer.withSnapshot { acquired.complete(Unit); hold.await() }
        }
        val progress = manager.installProgress
        try {
            runBlocking { withTimeout(5_000) { acquired.await() } }
            assertTrue(progress.state.value.entries.isEmpty())
            compose.onNodeWithText("Install").performClick()
            compose.waitUntil(10_000) { progress.state.value.entries.size == 1 }
            val id = progress.state.value.entries.single().id
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Install Python Runtime").fetchSemanticsNodes().isEmpty() }
            back()
            val entry = progress.state.value.entries.single()
            assertEquals(id, entry.id)
            assertEquals("runtime.python", entry.details.familyId)
            assertTrue(entry.running)
            progress.cancel(id)
            compose.waitUntil(10_000) { !progress.state.value.entries.single().running }
            assertEquals(InstallerProgressOutcome.CANCELED, progress.state.value.entries.single().outcome)
            assertTrue(manager.registry.list().isEmpty())
        } finally {
            progress.state.value.entries.filter { it.running }.forEach { progress.cancel(it.id) }
            reservation.cancel()
            hold.complete(Unit)
        }
    }

    @Test fun directPackageLinkReturnsToItsCaller() {
        mount("runtime.python")
        compose.onNodeWithText("Review installation").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun locallyImportedEditorPackageStaysManageableFromItsOriginalSource() {
        val archive = File(root, "local-syntax.vsix")
        val manifest = """{"name":"pocketsyntax","displayName":"Pocket syntax","publisher":"local","version":"1.0.0","contributes":{"languages":[{"id":"pocketsyntax","extensions":[".pocketsyntax"]}]}}"""
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("extension/package.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        DeclarativeExtensionRuntime.initialize(app)
        val installed = DeclarativeExtensionRuntime.install(archive)
        try {
            mount()
            compose.onNodeWithText("Installed").performClick()
            compose.onNodeWithText("Pocket syntax").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Manage it from the source where it was installed", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("1.0.0").assertIsDisplayed()
            compose.onNodeWithText("Uninstall").assertDoesNotExist()
            assertFalse(runBlocking { manager.marketplace.ownsInstalledRecord(installed.extensionId) })
            back()
            compose.onNodeWithText("Pocket syntax").assertIsDisplayed()
        } finally {
            DeclarativeExtensionRuntime.uninstall(installed.extensionId)
        }
    }

    @Test fun categoriesKeepAllFamiliesAccessibleAndDetailRestoresTheScrollAnchor() {
        mount()
        compose.onNodeWithText("See all extensions").performClick()
        compose.onNodeWithText("All categories").performClick()
        compose.onNodeWithText("Languages").performClick()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(12)
        val names = manager.snapshot.value.items.filter { it.family.category == ExtensionCategory.LANGUAGES }
            .map { it.family.name }.distinct()
        val entry = names.first { compose.onAllNodesWithText(it).fetchSemanticsNodes().any { node -> node.boundsInRoot.height > 20f } }
        val y = compose.onNodeWithText(entry).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText(entry).performClick()
        back()
        assertEquals(y, compose.onNodeWithText(entry).fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test @Config(qualifiers = "in-rID-w390dp-h844dp-mdpi")
    fun indonesianLibraryAndSdkReadTheRealOfferAndKeepDownloadBehindReview() {
        mount()
        compose.onNodeWithText("Pilih sesuai kebutuhan").assertIsDisplayed()
        capture("home-id")
        compose.onNodeWithText("Terpasang").performClick()
        compose.onNodeWithText("Ekstensi terpasang").assertIsDisplayed()
        back()
        compose.onNodeWithText("Buat aplikasi Android").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("SDK Platform")
        compose.onNodeWithText("Android SDK Platform").assertIsDisplayed()
        capture("search-id")
        compose.onNodeWithText("Pasang").performClick()
        compose.onNodeWithText("62,8 MiB").assertIsDisplayed()
        compose.onNodeWithText("Tinjau pemasangan").assertIsDisplayed()
        capture("detail-id")
        chooseVersion("Pilih versi", "Versi Android SDK Platform", "36", "Batal")
        assertFalse(manager.installer.state.value.running)
        assertTrue(manager.registry.list().isEmpty())
        compose.onNodeWithText("Kembali").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("SDK Platform")
    }

    @Test @Config(qualifiers = "w760dp-h340dp-land-mdpi")
    fun shortLandscapeCanReachTheLastTaskAndReturnFromDetails() {
        mount()
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToIndex(1 + ExtensionBrowseGroup.FORMAT.ordinal)
        compose.onNodeWithText("Format code").assertIsDisplayed().performClick()
        val results = extensionCatalogCards(manager.snapshot.value.items).filter { ExtensionBrowseGroup.FORMAT.includes(it.primary.family) }
        list.performScrollToIndex(1 + results.indexOfFirst { it.primary.family.id == "quality.ruff" }.also { assertTrue(it >= 0) })
        compose.onNodeWithText("Ruff").performClick()
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        compose.onNodeWithText("Ruff").assertIsDisplayed()
    }
}
