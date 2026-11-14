package com.baystudio.droide.core.storage

import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ExternalStorageToolsTest {
    private val root = Files.createTempDirectory("external-storage-tools").toFile()
    private val project = File(root, "project").apply { mkdir() }
    private val device = File(root, "device").apply { mkdir() }
    private val snapshot = StorageAccessSnapshot(true, true, true, listOf(SharedStorageVolume(device.path, "Device", true, true)), 3)
    private val source = object : StorageAccessSource {
        override fun refresh() = snapshot
        override fun requireCurrent(snapshot: StorageAccessSnapshot) { check(snapshot == this@ExternalStorageToolsTest.snapshot) }
    }
    private val files = FileRepository(project)
    private val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String) = "unused"
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = "unused"
    }
    private val bridge = ExternalIdeTools(files, null, lsp,
        PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), ApprovalManager(), {},
        storage = StorageOperationService(source, files))
    @After fun cleanup() { bridge.close(); PathSecurity.deleteTreeNoFollow(root) }

    @Test fun bridgeUsesSharedStorageServiceWithoutRelaxingLegacyWorkspacePaths() = runBlocking {
        val output = File(device, "result.txt")
        val result = bridge.call("droide_storage", buildJsonObject {
            put("operation", "write"); put("path", output.path); put("content", "from CLI")
        })
        assertEquals("from CLI", output.readText())
        assertTrue(result.contains("DROIDE_STORAGE_RESULT direct_shared_storage"))
        assertFalse(result.contains("source=editor_workspace"))
        assertTrue(runCatching { bridge.call("droide_read_file", buildJsonObject { put("path", output.path) }) }.isFailure)
        assertFalse(File(project, "result.txt").exists())
    }

    @Test fun catalogStatesMutationAndEnforcesBridgeRequestLimits() = runBlocking {
        val tool = bridge.definitions.map { it.jsonObject }.single { it["name"] == JsonPrimitive("droide_storage") }
        assertFalse(tool.getValue("annotations").jsonObject.getValue("readOnlyHint").jsonPrimitive.boolean)
        assertTrue(runCatching { bridge.call("droide_storage", buildJsonObject {
            put("operation", "write"); put("path", File(device, "large").path); put("content", "x".repeat(1501))
        }) }.isFailure)
        assertTrue(device.listFiles()!!.isEmpty())
    }

    @Test fun closingBridgePreventsFurtherStorageOperations() = runBlocking {
        bridge.close()
        assertTrue(runCatching { bridge.call("droide_storage", buildJsonObject { put("operation", "status") }) }.isFailure)
    }

    @Test fun importIdentifiesCanonicalProjectAndBecomesVisibleAtTheNextMirrorRefresh() = runBlocking {
        val mirrorRoot = File(root, "cli-copy")
        val mirror = LocalAgentWorkspaceMirror(project, mirrorRoot)
        mirror.refresh()
        val source = File(device, "asset.txt").apply { writeText("shared asset") }
        val result = bridge.call("droide_storage", buildJsonObject {
            put("operation", "import"); put("path", source.path); put("destination", "asset.txt")
        })
        assertTrue(result.contains("source=editor_workspace"))
        assertTrue(result.contains("CLI working copy may differ"))
        assertEquals("shared asset", File(project, "asset.txt").readText())
        assertFalse(File(mirrorRoot, "asset.txt").exists())
        mirror.refresh()
        assertEquals("shared asset", File(mirrorRoot, "asset.txt").readText())
    }
}
