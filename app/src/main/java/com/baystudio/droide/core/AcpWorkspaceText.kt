package com.baystudio.droide.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ACP text policy is separate from unrestricted-size streamed workspace artifacts. */
internal object AcpWorkspaceText {
    const val MAX_BYTES = 2_000_000L

    data class Read(val content: String, val sha256: String)
    private data class Observation(val content: String?, val version: StreamFileVersion)

    suspend fun version(files: FileRepository, relative: String): StreamFileVersion = observe(files, relative).version

    private suspend fun observe(files: FileRepository, relative: String): Observation = withContext(Dispatchers.IO) {
        val file = PathSecurity.resolveWithin(files.root, relative)
        // Validate text before hashing: a text write must not replace an arbitrary binary
        // or scan a multi-GiB output just because the new content happens to be small.
        val content = if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(file.toPath())) { "ACP text path cannot be a symbolic link" }
            files.readTextForEdit(relative, MAX_BYTES)
        } else null
        val version = StreamFileVersion.capture(files.root, relative)
        val expected = DiskFileVersion.fromText(content)
        check(version.existed == expected.existed && version.bytes == expected.bytes &&
            version.sha256 == expected.sha256) { "ACP text file changed during observation; existing data was preserved" }
        Observation(content, version)
    }

    suspend fun read(files: FileRepository, relative: String): Read {
        val observed = observe(files, relative)
        check(observed.version.existed) { "ACP execution file is unavailable: $relative" }
        observed.version.requireMatches(files.root, relative)
        return Read(requireNotNull(observed.content), observed.version.sha256)
    }
}

/** A bounded session history. Two short hashes remember evicted paths without retaining their names.
 * A collision only requests a new read. Ordinary first writes keep their existing admission path. */
internal class AcpReadVersions(private val capacity: Int = 1_024) {
    data class Version(val execution: String?, val canonical: String?)
    private val recent = LinkedHashMap<String, Version>()
    private val evictedPaths = java.util.BitSet()
    init { require(capacity > 0) }

    @Synchronized fun record(path: String, version: Version) {
        recent.remove(path)
        if (recent.size >= capacity) {
            val oldest = recent.keys.first()
            recent.remove(oldest)
            val hash = oldest.hashCode()
            evictedPaths.set(hash and 0xffff)
            evictedPaths.set(hash ushr 16)
        }
        recent[path] = version
    }

    @Synchronized fun permits(path: String, canonical: String?, execution: String?, hasProjection: Boolean): Boolean {
        val read = recent[path]
        if (read == null) {
            val hash = path.hashCode()
            return !(evictedPaths[hash and 0xffff] && evictedPaths[hash ushr 16])
        }
        return (read.canonical == null || read.canonical == canonical.orEmpty()) &&
            (!hasProjection || read.execution == execution)
    }

    @Synchronized fun clear() { recent.clear(); evictedPaths.clear() }
}

/** A local projection token is captured before approval; it never grants write permission. */
internal interface AcpTextProjection {
    val executionVersion: StreamFileVersion
    suspend fun requireUnchanged()
    suspend fun publish(content: String, requireCurrent: () -> Unit)
}
