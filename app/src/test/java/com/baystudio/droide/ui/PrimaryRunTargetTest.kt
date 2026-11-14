package com.baystudio.droide.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryRunTargetTest {
    @Test fun standaloneLanguagesDoNotInheritAndroidRoutingFromTheirParentWorkspace() {
        for (path in listOf("okjiss.kt", "Main.java", "app/src/main/kotlin/Main.kt", "app/src/main/java/Main.java",
            "untitled.cs", "tools/check.py", "main.js", "main.ts", "main.cpp", "demo.kts", "data.xml", "custom.abc")) {
            assertEquals(path, PrimaryRunTarget.FILE, primaryRunTarget(path, androidProject = true))
        }
    }

    @Test fun androidProjectDefinitionsRetainAppRouting() {
        for (path in listOf("app/src/main/AndroidManifest.xml", "app/build.gradle.kts", "settings.gradle", "")) {
            assertEquals(path, PrimaryRunTarget.ANDROID_APP, primaryRunTarget(path, androidProject = true))
        }
    }

    @Test fun previewsRemainPreviewsAndGenericProjectsNeverBecomeAndroid() {
        for (android in listOf(false, true)) {
            for (path in listOf("README.md", "web/index.html"))
                assertEquals(PrimaryRunTarget.PREVIEW, primaryRunTarget(path, android))
        }
        for (path in listOf("Main.kt", "Main.java", "main.cs", "main.py", ""))
            assertEquals(PrimaryRunTarget.FILE, primaryRunTarget(path, androidProject = false))
    }
}
