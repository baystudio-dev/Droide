package com.baystudio.droide.core.storage

import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class StorageOperationServiceTest {
    private val fixture = Files.createTempDirectory("device-storage-test").toFile()
    private val device = File(fixture, "volume").apply { mkdir() }
    private val project = File(fixture, "project").apply { mkdir() }
    private var snapshot = StorageAccessSnapshot(true, true, true, listOf(SharedStorageVolume(device.path, "Fixture", true, true)), 1)
    private val access = object : StorageAccessSource {
        override fun refresh() = snapshot
        override fun requireCurrent(snapshot: StorageAccessSnapshot) { check(snapshot == this@StorageOperationServiceTest.snapshot && snapshot.available) }
    }
    private val service = StorageOperationService(access, FileRepository(project))
    private val approvals = mutableListOf<Pair<String, String>>()
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(fixture) }

    private suspend fun call(operation: String, path: String = device.path, destination: String? = null,
                             content: String? = null, version: String? = null, mode: AgentMode = AgentMode.BUILD,
                             permit: suspend (String, String, String) -> Unit = { action, resource, _ -> approvals += action to resource }): JsonObject =
        Json.parseToJsonElement(service.execute(buildJsonObject {
            put("operation", operation); put("path", path)
            destination?.let { put("destination", it) }; content?.let { put("content", it) }; version?.let { put("version", it) }
        }, mode, {}, permit)).jsonObject

    @Test fun writesPublishExactBytesAndNeverOverwriteExistingFiles() = runBlocking {
        val file = File(device, "result.txt")
        call("write", file.path, content = "héllo 😀")
        assertEquals("héllo 😀", file.readText())
        assertTrue(runCatching { call("write", file.path, content = "wrong") }.isFailure)
        assertEquals("héllo 😀", file.readText())
        assertTrue(approvals.contains("edit" to file.path))
    }

    @Test fun directoryListingIncludesTypesWithoutReadingFileContents() = runBlocking {
        File(device, "folder").mkdir()
        File(device, "data.bin").writeBytes(byteArrayOf(0, -1, 0))
        val entries = call("list").getValue("result").jsonObject.getValue("entries").jsonArray
            .associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("kind").jsonPrimitive.content }
        assertEquals(mapOf("folder" to "directory", "data.bin" to "file"), entries)
    }

    @Test fun binaryCopiesAndProjectImportExportPreserveOriginalBytes() = runBlocking {
        val bytes = ByteArray(200000) { (it % 251).toByte() }
        val original = File(device, "original.bin").apply { writeBytes(bytes) }
        call("copy", original.path, File(device, "copy.bin").path)
        call("import", original.path, "asset.bin")
        call("export", "asset.bin", File(device, "export.bin").path)
        for (file in listOf(File(device, "copy.bin"), File(project, "asset.bin"), File(device, "export.bin"))) assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun staleDeleteVersionPreservesFileAndSuccessfulDeleteRetainsRecoveryCopy() = runBlocking {
        val file = File(device, "file.txt").apply { writeText("before") }
        val version = call("stat", file.path).getValue("result").jsonObject.getValue("version").jsonPrimitive.content
        file.writeText("after")
        assertTrue(runCatching { call("delete", file.path, version = version) }.isFailure)
        assertEquals("after", file.readText())
        val current = call("stat", file.path).getValue("result").jsonObject.getValue("version").jsonPrimitive.content
        val result = call("delete", file.path, version = current).getValue("result").jsonObject
        assertFalse(file.exists())
        assertEquals("after", File(result.getValue("retained_path").jsonPrimitive.content).readText())
        assertFalse(result.getValue("purged").jsonPrimitive.boolean)
    }

    @Test fun mutationIsRejectedInReadOnlyModesBeforePermissionsOrFilesystemWork() = runBlocking {
        for (mode in listOf(AgentMode.PLAN, AgentMode.REVIEW, AgentMode.EXPLORE)) {
            assertTrue(runCatching { call("write", File(device, "not-created").path, content = "x", mode = mode) }.isFailure)
        }
        assertTrue(approvals.isEmpty())
        assertTrue(device.listFiles()!!.isEmpty())
    }

    @Test fun revocationWhileAwaitingPermissionPreventsPublication() = runBlocking {
        val result = runCatching { call("write", File(device, "denied").path, content = "x", permit = { _, _, _ ->
            snapshot = snapshot.copy(enabled = false, generation = 2)
        }) }
        assertTrue(result.isFailure)
        assertTrue(device.listFiles()!!.isEmpty())
    }

    @Test fun symlinkEscapeAndPrivateWorkspaceTargetsAreRejected() = runBlocking {
        File(project, "secret").writeText("private")
        Files.createSymbolicLink(File(device, "alias").toPath(), project.toPath())
        for (path in listOf(File(device, "alias/secret").path, File(project, "secret").path, device.path + "/../project/secret")) {
            assertTrue(runCatching { call("read", path) }.isFailure)
        }
        assertTrue(approvals.isEmpty())
    }

    @Test fun textReadAndStatReturnMatchingVersionsAndUnicodeContent() = runBlocking {
        val file = File(device, "unicode.txt").apply { writeText("hello 日本語 😀") }
        val read = call("read", file.path).getValue("result").jsonObject
        val stat = call("stat", file.path).getValue("result").jsonObject
        assertEquals(file.readText(), read.getValue("text").jsonPrimitive.content)
        assertEquals(stat.getValue("version"), read.getValue("version"))
    }

    @Test fun existingMoveDestinationAndReadOnlyVolumePreserveBothFiles() = runBlocking {
        val first = File(device, "first").apply { writeText("one") }
        val second = File(device, "second").apply { writeText("two") }
        val version = call("stat", first.path).getValue("result").jsonObject.getValue("version").jsonPrimitive.content
        assertTrue(runCatching { call("move", first.path, second.path, version = version) }.isFailure)
        snapshot = snapshot.copy(volumes = snapshot.volumes.map { it.copy(writable = false) }, generation = 2)
        assertTrue(runCatching { call("write", File(device, "third").path, content = "three") }.isFailure)
        assertEquals("one", first.readText()); assertEquals("two", second.readText())
    }

    @Test fun statusReflectsAccessChangedWhilePermissionWasPending() = runBlocking {
        val result = call("status", permit = { _, _, _ ->
            snapshot = snapshot.copy(enabled = false, volumes = emptyList(), generation = 2)
        })
        assertFalse(result.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(2L, result.getValue("generation").jsonPrimitive.long)
        assertTrue(result.getValue("locations").jsonArray.isEmpty())
    }

    @Test fun directoryMovePreservesChildrenAndRejectsAReplacedDirectoryVersion() = runBlocking {
        val source = File(device, "folder").apply { mkdir() }
        File(source, "child.txt").writeText("keep")
        val version = call("stat", source.path).getValue("result").jsonObject.getValue("version").jsonPrimitive.content
        val original = File(device, "original")
        Files.move(source.toPath(), original.toPath())
        source.mkdir()
        File(source, "other.txt").writeText("new")
        val destination = File(device, "destination")
        assertTrue(runCatching { call("move", source.path, destination.path, version = version) }.isFailure)
        assertFalse(destination.exists())
        assertEquals("new", File(source, "other.txt").readText())
        val moved = call("move", original.path, destination.path, version = version).getValue("result").jsonObject
        assertTrue(moved.getValue("moved").jsonPrimitive.boolean)
        assertFalse(original.exists())
        assertEquals("keep", File(destination, "child.txt").readText())
    }

    @Test fun statusDoesNotCompleteForAnOwnerClosedDuringPermission() = runBlocking {
        var open = true
        val result = runCatching {
            service.execute(buildJsonObject { put("operation", "status") }, AgentMode.BUILD,
                current = { check(open) { "Owner closed" } }, allow = { _, _, _ -> open = false })
        }
        assertTrue(result.isFailure)
    }

    @Test fun importHonorsUnsavedDocumentAdmissionAndPreservesSource() = runBlocking {
        val source = File(device, "draft.txt").apply { writeText("imported text") }
        val guarded = FileRepository(project, beforeMutation = {
            throw WorkspaceDocumentConflictException(listOf("draft.txt"))
        })
        val result = runCatching {
            StorageOperationService(access, guarded).execute(buildJsonObject {
                put("operation", "import"); put("path", source.path); put("destination", "draft.txt")
            }, AgentMode.BUILD, {}, { _, _, _ -> })
        }
        assertTrue(result.isFailure)
        assertFalse(File(project, "draft.txt").exists())
        assertEquals("imported text", source.readText())
    }

    @Test fun moveNeverReportsSuccessForAReplacedSourceAtAdmissionBoundaries() = runBlocking {
        for (boundary in 1..32) {
            val source = File(device, "move-$boundary").apply { writeText("before") }
            val destination = File(device, "moved-$boundary")
            val version = call("stat", source.path).getValue("result").jsonObject.getValue("version").jsonPrimitive.content
            var checks = 0
            var replaced = false
            val result = runCatching {
                service.execute(buildJsonObject {
                    put("operation", "move"); put("path", source.path)
                    put("destination", destination.path); put("version", version)
                }, AgentMode.BUILD, current = {
                    if (++checks == boundary && source.exists() && !destination.exists()) {
                        val replacement = File(device, "replacement-$boundary").apply { writeText("after") }
                        Files.move(replacement.toPath(), source.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        replaced = true
                    }
                }, allow = { _, _, _ -> })
            }
            if (replaced) {
                assertTrue("Replacement at boundary $boundary was reported as a successful move", result.isFailure)
                assertEquals("after", (if (source.exists()) source else destination).readText())
            } else {
                assertTrue(result.isSuccess)
                assertEquals("before", destination.readText())
            }
        }
    }
}
