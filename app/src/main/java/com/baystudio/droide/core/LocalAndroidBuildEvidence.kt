package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID


internal object LocalAndroidBuildEvidence {
    data class Session(val directory: File, val initScript: File, val manifest: File)
    private const val MAX_BYTES = 512L * 1024L
    private const val MAX_ROOTS = 2048

    fun supportsBuildEvents(version: String?): Boolean =
        version?.let(GradleRuntimeCompatibility::parse)?.let { it >= GradleRuntimeCompatibility.Version(8, 1) } == true

    fun prepare(projectRoot: File, script: ByteArray): Session {
        require(script.size in 1..65_536)
        val root = projectRoot.canonicalFile
        val parent = root.resolve(".droide/build-evidence")
        require(parent.canonicalFile == parent) { "Unsafe Gradle evidence directory" }
        Files.createDirectories(parent.toPath())
        val hash = MessageDigest.getInstance("SHA-256").digest(script).joinToString("") { "%02x".format(it) }
        val init = File(parent, "outputs-$hash.gradle")
        if (!Files.isRegularFile(init.toPath(), NOFOLLOW_LINKS) || init.length() != script.size.toLong() ||
            !init.readBytes().contentEquals(script)) {
            val temp = Files.createTempFile(parent.toPath(), ".outputs-", ".tmp")
            try {
                Files.write(temp, script)
                Files.move(temp, init.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
        val directory = Files.createDirectory(parent.resolve(UUID.randomUUID().toString()).toPath()).toFile()
        return try {
            Session(directory, init, Files.createFile(directory.resolve("outputs.tsv").toPath()).toFile())
        } catch (failure: Throwable) {
            PathSecurity.deleteTreeNoFollow(directory)
            throw failure
        }
    }

    fun readOutputRoots(projectRoot: File, manifest: File): List<File> {
        val root = projectRoot.canonicalFile
        require(PathSecurity.contains(root, manifest) && manifest.isFile && !PathSecurity.isSymbolicLink(manifest)) { "Invalid Gradle artifact evidence" }
        require(manifest.length() <= MAX_BYTES) { "Gradle artifact evidence exceeds size limit" }
        val result = linkedSetOf<File>()
        manifest.bufferedReader(Charsets.UTF_8).useLines { lines ->
            var count = 0
            lines.forEach { line ->
                require(++count <= MAX_ROOTS) { "Too many Gradle artifact output roots" }
                val columns = line.split('\t')
                require(columns.size == 2 && columns[0] in setOf("EXECUTED", "UP-TO-DATE", "FROM-CACHE")) { "Invalid Gradle task outcome" }
                val path = columns[1]
                require(path.length in 1..4096 && path.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid Gradle output path" }
                val file = File(path)
                require(file.isAbsolute && !PathSecurity.isSymbolicLink(file)) { "Unsafe Gradle output root" }
                val canonical = file.canonicalFile
                require(PathSecurity.contains(root, canonical)) { "Gradle output escaped workspace" }
                if (canonical.exists()) result += canonical
            }
        }
        return result.toList()
    }
}
