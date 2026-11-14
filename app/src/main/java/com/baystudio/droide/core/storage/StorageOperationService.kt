package com.baystudio.droide.core.storage

import com.baystudio.droide.core.*
import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal class StorageOperationService(
    private val controller: StorageAccessSource,
    private val files: FileRepository,
) {
    suspend fun execute(args: JsonObject, mode: AgentMode, current: () -> Unit,
                        allow: suspend (String, String, String) -> Unit): String = withContext(Dispatchers.IO) {
        val operation = args.string("operation").ifBlank { "status" }
        require(operation in OPERATIONS) { "Unknown storage operation" }
        require(operation !in MUTATIONS || mode == AgentMode.BUILD) { "Storage changes require BUILD mode" }
        val job = currentCoroutineContext()
        fun checkOwner() { job.ensureActive(); current() }
        if (operation in setOf("status", "locations")) {
            checkOwner()
            allow("read", "device-storage:status", "device-storage:status")
            checkOwner()
            val latest = controller.refresh()
            checkOwner()
            return@withContext status(latest).toString()
        }
        val snapshot = controller.refresh()
        fun checkCurrent() { checkOwner(); controller.requireCurrent(snapshot) }
        checkCurrent()
        val path = args.string("path")
        val source = if (operation == "export") null else target(snapshot, path)
        val destination = if (operation in setOf("write", "mkdir")) source else
            if (operation in setOf("copy", "move", "export")) target(snapshot, args.string("destination")) else null
        suspend fun authorize(target: Target, action: String) {
            checkCurrent()
            val grant = "device-storage:${snapshot.generation}:${target.volume.path}"
            allow("external_directory", target.file.path, grant)
            allow(action, target.file.path, grant)
            checkCurrent()
            if (action == "edit") check(target.volume.writable) { "Storage volume is read only" }
        }
        source?.let { authorize(it, if (operation in setOf("write", "mkdir", "move", "delete")) "edit" else "read") }
        if (destination != null && destination !== source) authorize(destination, "edit")
        if (operation == "import") allow("edit", projectPath(args.string("destination")), args.string("destination"))
        if (operation == "export") allow("read", projectPath(path), path)
        mutationMutex.withLock {
            checkCurrent()
            val result = when (operation) {
                "list" -> requireNotNull(source).open().use { anchor ->
                    val children = mutableListOf<JsonElement>()
                    var truncated = false
                    try {
                        anchor.forEachChild(source.relative) { name ->
                            checkCurrent()
                            if (children.size >= 150) throw ListingComplete()
                            val relative = listOf(source.relative, name).filter { it.isNotEmpty() }.joinToString("/")
                            children += buildJsonObject { put("name", name); put("kind", anchor.kind(relative).name.lowercase()) }
                        }
                    } catch (_: ListingComplete) { truncated = true }
                    buildJsonObject { put("entries", JsonArray(children)); put("truncated", truncated) }
                }
                "stat" -> requireNotNull(source).open().use { anchor ->
                    val kind = if (source.relative.isEmpty()) AnchoredWorkspaceFiles.Kind.DIRECTORY else anchor.kind(source.relative)
                    buildJsonObject {
                        put("kind", kind.name.lowercase())
                        if (kind == AnchoredWorkspaceFiles.Kind.FILE) anchor.openRead(source.relative).use { input ->
                            put("bytes", input.observe().attributes.size())
                            put("version", version(input, ::checkCurrent))
                        }
                        if (kind == AnchoredWorkspaceFiles.Kind.DIRECTORY) put("version", "directory:" + anchor.directoryKey(source.relative))
                    }
                }
                "read" -> requireNotNull(source).open().use { anchor -> anchor.openRead(source.relative).use { input ->
                    val before = input.observe()
                    require(before.attributes.size() <= MAX_TEXT_BYTES) { "Use terminal tools to read this large file in bounded chunks" }
                    val bytes = input.readBytesBounded(MAX_TEXT_BYTES, ::checkCurrent)
                    check(sameObservation(input.observe(), before)) { "File changed while reading; retry" }
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
                    require(offset in 0..text.length) { "Invalid text offset" }
                    var end = minOf(text.length, offset + 4000)
                    if (end < text.length && end > offset && text[end - 1].isHighSurrogate()) end--
                    buildJsonObject { put("text", text.substring(offset, end)); put("version", hash(bytes))
                        if (end < text.length) put("next_offset", end) }
                } }
                "mkdir" -> requireNotNull(source).open().use { anchor ->
                    require(source.relative.isNotEmpty()) { "Cannot replace a volume root" }
                    val receipt = anchor.createDirectory(source.relative)
                    check(receipt.published) { "Directory was not created (errno=${receipt.error}); existing contents were preserved" }
                    buildJsonObject { put("created", receipt.published); put("durable", receipt.durable); put("errno", receipt.error) }
                }
                "write" -> {
                    require((args["content"] as? JsonPrimitive)?.isString == true) { "write requires string content" }
                    val bytes = args.string("content").toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_TEXT_BYTES) { "Use a file transfer or terminal for larger content" }
                    bytes.inputStream().use { publish(requireNotNull(destination), it, bytes.size.toLong(), ::checkCurrent) }
                }
                "copy", "export" -> {
                    val root = if (source != null) File(source.volume.path) else files.root
                    val relative = source?.relative ?: projectPath(path)
                    AnchoredWorkspaceFiles(root).use { anchor -> anchor.openRead(relative).use { input ->
                        val before = input.observe()
                        publish(requireNotNull(destination), input, before.attributes.size(), ::checkCurrent) {
                            check(sameObservation(input.observe(), before)) { "Source changed during transfer" }
                        }
                    } }
                }
                "import" -> requireNotNull(source).open().use { anchor -> anchor.openRead(source.relative).use { input ->
                    val before = input.observe()
                    require(before.attributes.size() <= MAX_TRANSFER_BYTES) { "File exceeds the structured transfer limit" }
                    val saved = files.writeStream(projectPath(args.string("destination")), input,
                        expectedBytes = before.attributes.size(), requireAbsent = true,
                        beforePublication = { checkCurrent(); check(sameObservation(input.observe(), before)) { "Source changed during import" } })
                    buildJsonObject { put("bytes", saved); put("scope", "project"); put("saved", true) }
                } }
                "move", "delete" -> moveOrDelete(requireNotNull(source), destination, args.string("version"), ::checkCurrent)
                else -> error("Unsupported operation")
            }
            buildJsonObject {
                put("operation", operation); put("path", source?.file?.path ?: path)
                destination?.let { put("destination", it.file.path) }
                put("scope", if (operation == "import") "project" else "shared-storage-direct")
                put("result", result)
            }.toString()
        }
    }

    private fun moveOrDelete(source: Target, destination: Target?, expected: String, current: () -> Unit): JsonObject {
        require(source.relative.isNotEmpty() && expected.isNotBlank()) { "Read stat/version first; volume roots cannot be moved or deleted" }
        source.open().use { anchor ->
            val kind = anchor.kind(source.relative)
            val directoryKey = if (kind == AnchoredWorkspaceFiles.Kind.DIRECTORY) anchor.directoryKey(source.relative) else null
            (if (directoryKey == null) anchor.openRead(source.relative) else null).use { original ->
                val before = original?.observe()
                val actualVersion = if (original != null) version(original, current) else "directory:$directoryKey"
                check(actualVersion == expected) { "File version changed; inspect it again" }
                fun verifySource() {
                    if (before != null) anchor.openRead(source.relative).use { input ->
                        check(sameObservation(input.observe(), before)) { "Source changed before move; inspect it again" }
                    } else check(anchor.directoryKey(source.relative) == directoryKey) { "Directory changed before move; inspect it again" }
                }
                if (destination != null) {
                    require(source.volume.path == destination.volume.path) { "Cross-volume move requires copy, verification, then explicit delete" }
                    current()
                    destination.open().use { target ->
                        verifySource()
                        anchor.moveNoReplace(source.relative, target, destination.relative)
                        try {
                            if (before != null) target.openRead(destination.relative).use { moved ->
                                val after = moved.observe()
                                // Rename changes ctime; compare identity/content metadata and the still-pinned source.
                                check(sameFileMetadata(after, before) && sameObservation(after, requireNotNull(original).observe())) {
                                    "File changed during move"
                                }
                            } else check(target.directoryKey(destination.relative) == directoryKey) { "Directory changed during move" }
                        } catch (failure: Exception) {
                            throw java.io.IOException("Move needs review; inspect source and destination ${destination.file}. No data was purged.", failure)
                        }
                    }
                    return buildJsonObject { put("moved", true) }
                }
                require(kind == AnchoredWorkspaceFiles.Kind.FILE) { "Structured delete accepts regular files only; use an approved terminal command for directories" }
                val retained = ".droide-removed-${UUID.randomUUID()}"
                val parentRelative = source.relative.substringBeforeLast('/', "")
                anchor.openDirectory(parentRelative).use { parent ->
                    current()
                    verifySource()
                    parent.moveNoReplace(source.file.name, parent, retained)
                    try {
                        parent.openRead(retained).use { input ->
                            check(version(input, current) == expected) { "File changed; removed copy retained for recovery" }
                            current()
                            return buildJsonObject { put("removed", true); put("purged", false)
                                put("retained_path", File(source.file.parentFile, retained).path) }
                        }
                    } catch (failure: Exception) {
                        throw java.io.IOException("Removal needs review; data retained at ${File(source.file.parentFile, retained)}", failure)
                    }
                }
            }
        }
    }

    private fun publish(target: Target, input: InputStream, bytes: Long, current: () -> Unit,
                        verifySource: () -> Unit = {}): JsonObject {
        require(bytes in 0..MAX_TRANSFER_BYTES && target.relative.isNotEmpty()) { "Unsupported transfer size or destination" }
        val stage = ".droide-transfer-${UUID.randomUUID()}"
        val parentRelative = target.relative.substringBeforeLast('/', "")
        target.open().use { volume -> volume.openDirectory(parentRelative).use { parent ->
            check(parent.kind(target.file.name) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Destination exists; use a new name. Existing files were preserved" }
            var created = false
            var published = false
            try {
                parent.createNew(stage).use { output ->
                    created = true
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        current()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= bytes) { "Source size changed" }
                        output.write(buffer, 0, count)
                    }
                    check(total == bytes) { "Transfer ended before all bytes were received" }
                    verifySource(); current(); output.forceContents()
                }
                current()
                parent.moveNoReplace(stage, parent, target.file.name)
                published = true
                val durable = runCatching { parent.forceDirectory() }.isSuccess
                return buildJsonObject { put("created", true); put("bytes", bytes); put("durable", durable)
                    if (!durable) put("notice", "File created; filesystem did not confirm directory synchronization. Inspect the destination before retrying.") }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw java.io.IOException(if (created && !published) "Transfer incomplete; retained at ${File(target.file.parentFile, stage)}. ${failure.message}"
                    else "Transfer failed: ${failure.message}", failure)
            }
        } }
    }

    private data class Target(val file: File, val volume: SharedStorageVolume) {
        val relative: String get() = file.relativeTo(File(volume.path)).invariantSeparatorsPath.let { if (it == ".") "" else it }
        fun open() = AnchoredWorkspaceFiles(File(volume.path))
    }

    private fun target(snapshot: StorageAccessSnapshot, path: String): Target {
        val file = ExternalMountPlan.resolve(snapshot, path)
        return Target(file, requireNotNull(snapshot.volumeFor(file.path)))
    }

    private fun projectPath(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && AgentWorkspacePathPolicy.visible(path)) { "Use a visible project-relative path" }
        files.resolveChecked(path)
        return path
    }

    private fun version(input: AnchoredWorkspaceFiles.OpenedFile, current: () -> Unit): String {
        val before = input.observe()
        require(before.attributes.size() <= MAX_TRANSFER_BYTES) { "Use terminal tools for this file size" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            current()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= before.attributes.size()) { "File grew during observation" }
            digest.update(buffer, 0, count)
        }
        check(total == before.attributes.size() && sameObservation(input.observe(), before)) { "File changed during observation" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun InputStream.readBytesBounded(limit: Int, current: () -> Unit): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            current()
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "File grew past text limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class ListingComplete : RuntimeException()
    companion object {
        private val mutationMutex = Mutex()
        private const val MAX_TEXT_BYTES = 65536
        private const val MAX_TRANSFER_BYTES = 4L * 1024 * 1024 * 1024
        val OPERATIONS = setOf("status", "locations", "list", "stat", "read", "write", "mkdir", "copy", "move", "delete", "import", "export")
        private val MUTATIONS = setOf("write", "mkdir", "copy", "move", "delete", "import", "export")
        private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun sameObservation(a: AnchoredWorkspaceFiles.Observation, b: AnchoredWorkspaceFiles.Observation) =
            sameFileMetadata(a, b) && a.changed == b.changed
        private fun sameFileMetadata(a: AnchoredWorkspaceFiles.Observation, b: AnchoredWorkspaceFiles.Observation) =
            a.attributes.fileKey() == b.attributes.fileKey() && a.attributes.size() == b.attributes.size() &&
                a.attributes.lastModifiedTime() == b.attributes.lastModifiedTime()
        private fun status(snapshot: StorageAccessSnapshot) = buildJsonObject {
            put("supported", snapshot.supported); put("enabled", snapshot.enabled); put("granted", snapshot.granted)
            put("generation", snapshot.generation)
            put("locations", buildJsonArray { snapshot.volumes.forEach { volume -> add(buildJsonObject {
                put("path", volume.path); put("label", volume.label); put("writable", volume.writable)
                if (volume.primary) put("terminal_alias", "/sdcard")
            }) } })
            put("note", "Shared-file changes are direct, outside project diff/Undo. New mounts require a new terminal or reconnected CLI. Private app data remains Android-protected. Project import/export uses saved bytes; shared storage is not a POSIX build workspace.")
        }
    }
}
