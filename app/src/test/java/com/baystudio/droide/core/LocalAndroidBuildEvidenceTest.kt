package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalAndroidBuildEvidenceTest {
    @Test fun upToDateAndCacheHitArtifactsRemainDiscoverableWithOldTimestamps() = withFixture { root ->
        val session = LocalAndroidBuildEvidence.prepare(root, "script".toByteArray())
        val apk = root.resolve("app/build/outputs/apk/debug/app-debug.apk").apply {
            parentFile.mkdirs(); writeText("fixture"); setLastModified(1000)
        }
        for (outcome in listOf("UP-TO-DATE", "FROM-CACHE", "EXECUTED")) {
            session.manifest.writeText("$outcome\t${apk.parentFile.canonicalPath}\n")
            val roots = LocalAndroidBuildEvidence.readOutputRoots(root, session.manifest)
            assertEquals(listOf(apk.canonicalFile), LocalAndroidBuildArtifactCollector.collect(root, ":app:assembleDebug", roots))
        }
    }

    @Test fun skippedTaskCannotAdmitOldArtifacts() = withFixture { root ->
        val session = LocalAndroidBuildEvidence.prepare(root, "script".toByteArray())
        session.manifest.writeText("SKIPPED\t${root.canonicalPath}\n")
        assertThrows(IllegalArgumentException::class.java) { LocalAndroidBuildEvidence.readOutputRoots(root, session.manifest) }
    }

    @Test fun evidenceCannotAdmitOutsideFiles() = withFixture { root ->
        val session = LocalAndroidBuildEvidence.prepare(root, "script".toByteArray())
        session.manifest.writeText("EXECUTED\t${root.parentFile.canonicalPath}\n")
        assertThrows(IllegalArgumentException::class.java) { LocalAndroidBuildEvidence.readOutputRoots(root, session.manifest) }
    }

    @Test fun eachInvocationHasAFreshManifestAndIndependentDirectory() = withFixture { root ->
        val first = LocalAndroidBuildEvidence.prepare(root, "script".toByteArray())
        first.manifest.writeText("old evidence")
        val second = LocalAndroidBuildEvidence.prepare(root, "script".toByteArray())
        assertEquals(first.initScript, second.initScript)
        assertEquals("old evidence", first.manifest.readText())
        assertNotEquals(first.directory, second.directory)
        assertEquals("", second.manifest.readText())
    }

    @Test fun scriptIsStableAndRepairedIfModifiedOrReplacedWithALink() = withFixture { root ->
        val bytes = "original script".toByteArray()
        val original = LocalAndroidBuildEvidence.prepare(root, bytes).initScript
        assertTrue(original.setLastModified(8_000L))
        assertEquals(8_000L, LocalAndroidBuildEvidence.prepare(root, bytes).initScript.lastModified())
        original.writeText("changed")
        assertArrayEquals(bytes, LocalAndroidBuildEvidence.prepare(root, bytes).initScript.readBytes())
        val other = root.resolve("user.gradle").apply { writeText("user data") }
        assertTrue(original.delete())
        Files.createSymbolicLink(original.toPath(), other.toPath())
        assertArrayEquals(bytes, LocalAndroidBuildEvidence.prepare(root, bytes).initScript.readBytes())
        assertEquals("user data", other.readText())
        assertNotEquals(original, LocalAndroidBuildEvidence.prepare(root, "updated".toByteArray()).initScript)
    }

    @Test fun taskEventsUseStableConfigurationCacheApiOnKnownModernWrappers() {
        assertTrue(LocalAndroidBuildEvidence.supportsBuildEvents("8.13"))
        assertTrue(LocalAndroidBuildEvidence.supportsBuildEvents("9.0"))
        assertFalse(LocalAndroidBuildEvidence.supportsBuildEvents("7.6"))
        assertFalse(LocalAndroidBuildEvidence.supportsBuildEvents(null))
    }

    private inline fun withFixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-build-evidence-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
