package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h860dp")
class PrimaryRunHeaderTest {
    @get:Rule val compose = createComposeRule()
    private var runs = 0
    private var devices = 0
    private var localAppRuns = 0
    private var path by mutableStateOf("untitled.cs")
    private var busy by mutableStateOf(false)

    private fun mount(compact: Boolean = true, android: Boolean = true, connected: Boolean = false) {
        compose.setContent {
            DroideTheme {
                DroideIdeHeader(
                    DroideIdeHeaderState("Project", path, compact, false, false, false, false,
                        busy, android, connected, false, false, false, emptyList()),
                    DroideIdeHeaderActions(
                        onProjects = {}, onRun = { runs++ }, onBuildDebug = {}, onTest = {},
                        onTestExplorer = {}, onLint = {}, onLintFile = {}, onReleaseApk = {}, onReleaseBundle = {},
                        onBuildOutput = {}, onApkAnalyzer = {}, onCancel = {}, onDebugAndroid = {},
                        onRunAndroidApp = { localAppRuns++ },
                        onInstallRun = {}, onLogcat = {}, onLocalLinux = {},
                        onAndroidDevelopment = { devices++ }, onQuickOpen = {}, onSearchFiles = {},
                        onWorkspaceSymbols = {}, onProblems = {}, onTerminal = {}, onGit = {},
                        onReviewChanges = {}, onIsolatedWorkspaces = {}, onCapabilityHealth = {},
                        onTasks = {}, onAgent = {}, onFocusMode = {}, onCommandPalette = {},
                        onRunDebug = {}, onSessions = {}, onExtensions = {}, onConnectProvider = {},
                        onModels = {}, onBilling = {}, onSettings = {}, onMcpRetry = {}, onManageMcp = {},
                    ),
                )
            }
        }
    }

    @Test fun csharpInAndroidLookingWorkspaceStillOffersRunCurrentFile() {
        mount()
        compose.onNodeWithContentDescription("Run current file").performClick()
        assertEquals(1, runs)
        assertEquals(0, devices)
    }

    @Test fun switchingFromBuildDefinitionToKotlinChangesTheRunAction() {
        path = "app/build.gradle.kts"
        mount(compact = false)
        compose.onNodeWithText("Run App").assertExists()
        compose.runOnIdle { path = "okjiss.kt" }
        compose.onNodeWithText("Run", substring = false).performClick()
        assertEquals(1, runs)
        assertEquals(0, devices)
    }

    @Test fun htmlStillOffersPreviewInAnAndroidProject() {
        path = "app/src/main/assets/index.html"
        mount()
        compose.onNodeWithContentDescription("Preview current file").performClick()
        assertEquals(1, runs)
        assertEquals(0, devices)
    }

    @Test fun kotlinAndJavaOfferFileRunEvenWithAConnectedAndroidDevice() {
        path = "okjiss.kt"
        mount(connected = true)
        compose.onNodeWithContentDescription("Run current file").performClick()
        compose.runOnIdle { path = "app/src/main/java/Hello.java" }
        compose.onNodeWithContentDescription("Run current file").performClick()
        assertEquals(2, runs)
        assertEquals(0, devices)
        assertEquals(0, localAppRuns)
    }

    @Test fun androidRunWithoutADeviceConnectionStaysEnabledAndDoesNotOpenDeviceSetup() {
        path = "app/src/main/AndroidManifest.xml"
        mount(connected = false)
        compose.onNodeWithContentDescription("Build, install and run Android app").assertIsEnabled().performClick()
        assertEquals(1, runs)
        assertEquals(0, devices)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Run on connected device").assertIsNotEnabled()
    }

    @Test fun androidAppCanBeRunExplicitlyWhileEditingASourceFileWithoutAConnection() {
        path = "okjiss.kt"
        mount(connected = false)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Run Android app on this device").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, localAppRuns)
        assertEquals(0, runs)
        assertEquals(0, devices)
    }

    @Test fun runningWorkDisablesRunWithoutChangingItsDestination() {
        mount(android = false)
        compose.runOnIdle { busy = true }
        compose.onNodeWithContentDescription("Run current file").assertIsNotEnabled()
        assertEquals(0, runs)
        assertEquals(0, devices)
    }
}
