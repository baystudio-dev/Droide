package com.baystudio.droide.core

import java.io.File
import java.util.UUID

/** An immutable route is published only after its private working copy has finished enrollment. */
internal class AgentWorkspaceCopies(private val original: File) {
    private val parent = checkNotNull(original.parentFile)
    private fun route(index: Int) = ".${original.name}-session-$index"
    private fun container(id: UUID) = ".${original.name}-copy-$id"

    private fun exists(files: AnchoredWorkspaceFiles, index: Int): Boolean = when (files.kind(route(index))) {
        AnchoredWorkspaceFiles.Kind.ABSENT -> false
        AnchoredWorkspaceFiles.Kind.FILE -> true
        else -> error("CLI session storage changed. Existing copies were preserved.")
    }

    fun latest(): Int = AnchoredWorkspaceFiles(parent).use { files ->
        var low = 0
        var high = 1
        while (exists(files, high)) {
            low = high
            high = Math.multiplyExact(high, 2)
        }
        while (high - low > 1) {
            val middle = low + (high - low) / 2
            if (exists(files, middle)) low = middle else high = middle
        }
        low
    }

    fun working(index: Int): File {
        require(index >= 0)
        if (index == 0) return original
        return AnchoredWorkspaceFiles(parent).use { files ->
            val bytes = files.openRead(route(index)).use { input ->
                val size = input.observe().attributes.size()
                check(size in 1..1024)
                val result = ByteArray(size.toInt())
                java.io.DataInputStream(input).readFully(result)
                check(input.read() == -1)
                result
            }
            val id = UUID.fromString(bytes.toString(Charsets.UTF_8).substringBefore('\n'))
            check(bytes.contentEquals(record(files, index, id))) { "Earlier CLI copy identity changed." }
            File(File(parent, container(id)), "workspace")
        }
    }

    suspend fun next(blocked: Int, enroll: suspend (File) -> Unit): Int = AnchoredWorkspaceFiles(parent).use { files ->
        val index = Math.incrementExact(blocked)
        if (exists(files, index)) return@use index
        val id = UUID.randomUUID()
        val name = container(id)
        val location = File(parent, name)
        check(files.createDirectory(name).durable) { "Could not reserve storage for the CLI session." }
        files.openDirectory(name).use { candidate ->
            try {
                enroll(File(location, "workspace"))
                candidate.createNew("route").use { it.write(record(files, index, id)); it.force(384) }
            } catch (failure: Throwable) {
                try {
                    if (files.directoryKey(name) == candidate.directoryKey()) PathSecurity.deleteTreeNoFollow(location)
                } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
            val move = candidate.moveNoReplaceDurable("route", files, route(index))
            if (!move.durable) {
                check(!move.published && move.error == android.system.OsConstants.EEXIST && exists(files, index)) {
                    "Could not publish the CLI working copy. Existing files were preserved."
                }
                files.forceDirectory()
                // A competing host published first. No process received this private candidate.
                if (files.directoryKey(name) == candidate.directoryKey()) PathSecurity.deleteTreeNoFollow(location)
            }
        }
        index
    }

    private fun record(files: AnchoredWorkspaceFiles, index: Int, id: UUID): ByteArray {
        val path = container(id)
        return "$id\n${route(index)}\n${files.directoryKey()}\n${files.directoryKey(path)}\n${files.directoryKey("$path/workspace")}\n"
            .toByteArray(Charsets.UTF_8)
    }
}
