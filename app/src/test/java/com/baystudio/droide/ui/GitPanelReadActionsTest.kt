package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import com.baystudio.droide.core.ExportWriterAdmission
import com.baystudio.droide.core.GitManager
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GitPanelReadActionsTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("git-read-ui-").toFile()
    private val git = GitManager(root)
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun externalStagingUpdatesTheVisiblePanelAtWorkspaceInvalidation() {
        runBlocking {
            git.init()
            git.configureIdentity("Test", "test@example.invalid")
        }
        root.resolve("edited.txt").writeText("from terminal")
        val revision = androidx.compose.runtime.mutableLongStateOf(0L)
        compose.setContent { DroideTheme { GitPanel(git, revision.longValue, {}, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Log") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        list.performScrollToNode(hasText("Changes (1)"))
        runBlocking { git.stage("edited.txt") }
        compose.runOnIdle { revision.longValue++ }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Changes (0)")).fetchSemanticsNodes().isNotEmpty() }
        list.performScrollToNode(hasText("Staged Changes (1)"))
        compose.onNodeWithText("Staged Changes (1)").assertExists()
    }

    @Test fun blockedReadReportsExportConflictAndRecoversWithoutCrashing() {
        runBlocking { git.init() }
        root.resolve("keep.txt").writeText("unsaved working tree content")
        compose.setContent { DroideTheme { GitPanel(git) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Log") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        for (label in listOf("Log", "Diff", "Branches")) {
            val capture = ExportWriterAdmission.beginCapture(root)
            try {
                compose.onNodeWithText(label).performScrollTo().performClick()
                list.performScrollToNode(hasText("git error:", substring = true))
                compose.onNodeWithText("git error:", substring = true).assertExists()
            } finally { capture.close() }
            list.performScrollToIndex(0)
            compose.onNodeWithContentDescription("Refresh").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                    ExportWriterAdmission.snapshotBlockers(root).isEmpty()
            }
        }
        assertEquals("unsaved working tree content", root.resolve("keep.txt").readText())
    }
}
