package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object AndroidSdkComponentArchive {
    suspend fun verify(component: AndroidLocalComponentCatalogEntry, archive: File) {
        check(archive.length() == component.sizeBytes) { "Android SDK component size changed after verified download" }
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val context = currentCoroutineContext()
        val buffer = ByteArray(64 * 1024)
        archive.inputStream().use { input ->
            while (true) {
                context.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                sha256.update(buffer, 0, read)
                sha1.update(buffer, 0, read)
            }
        }
        fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        check(hex(sha256) == component.sha256 && hex(sha1) == component.upstreamSha1) {
            "Android SDK component does not match its pinned Google archive"
        }
    }

    suspend fun extract(
        component: AndroidLocalComponentCatalogEntry,
        archive: File,
        destination: File,
        requireSpace: suspend (Long) -> Unit,
    ) {
        val root = destination.canonicalFile
        require(root.isDirectory && !PathSecurity.isSymbolicLink(destination)) { "Invalid Android SDK staging directory" }
        val prefix = "${component.archiveRoot}/"
        val allowed = if (component.kind == AndroidLocalComponentKind.BUILD_TOOLS_ARM64) AndroidBuildToolsPayload.archiveFiles else null
        val context = currentCoroutineContext()
        ZipFile(archive).use { zip ->
            require(zip.size() in 1..component.maxFiles * 2) { "Android SDK component ZIP has too many entries" }
            val paths = HashSet<String>()
            val selected = ArrayList<ZipEntry>()
            var expandedBytes = 0L
            for (member in zip.entries()) {
                context.ensureActive()
                if (member.name == prefix && member.isDirectory) continue
                require(member.name.startsWith(prefix)) { "Android SDK component ZIP escaped its pinned archive root" }
                val path = member.name.removePrefix(prefix).removeSuffix("/")
                require(path.length in 1..500 && '\\' !in path && path.none { it.code < 32 || it.code == 127 }) { "Unsafe Android SDK component ZIP path" }
                val parts = path.split('/')
                require(parts.size <= 32 && parts.all { it.isNotBlank() && it.length <= 180 && it != "." && it != ".." }) { "Unsafe Android SDK component ZIP path" }
                require(paths.add(path)) { "Android SDK component ZIP contains duplicate paths" }
                if (member.isDirectory || (allowed != null && path !in allowed)) continue
                require(selected.size < component.maxFiles) { "Android SDK component ZIP has too many extracted files" }
                require(member.size in 0..component.maxUnpackedBytes && member.compressedSize >= 0) { "Invalid Android SDK component ZIP member size" }
                require(member.size <= Math.multiplyExact(member.compressedSize, 250L)) { "Android SDK component ZIP has an unsafe compression ratio" }
                expandedBytes = Math.addExact(expandedBytes, member.size)
                require(expandedBytes <= component.maxUnpackedBytes) { "Android SDK component ZIP exceeded its expanded-byte bound" }
                selected += member
            }
            require(selected.isNotEmpty()) { "Android SDK component ZIP contains no admitted files" }
            requireSpace(expandedBytes)
            val buffer = ByteArray(64 * 1024)
            for (member in selected) {
                context.ensureActive()
                val output = root.resolve(member.name.removePrefix(prefix))
                require(output.canonicalFile == output) { "Android SDK component ZIP escaped staging" }
                check(output.parentFile!!.let { it.isDirectory || it.mkdirs() } && output.createNewFile()) { "Android SDK component path conflicts with an existing file" }
                zip.getInputStream(member).use { input ->
                    FileOutputStream(output).use { stream ->
                        var written = 0L
                        while (true) {
                            context.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            require(written <= member.size) { "Android SDK component ZIP exceeded its declared size" }
                            stream.write(buffer, 0, n)
                        }
                        require(written == member.size) { "Incomplete Android SDK component ZIP member" }
                        stream.fd.sync()
                    }
                }
            }
        }
    }
}
