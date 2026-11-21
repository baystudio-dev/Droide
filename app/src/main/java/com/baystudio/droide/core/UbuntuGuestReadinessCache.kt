package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal fun ubuntuBinaryAttributes(binary: Path, store: Path): PosixFileAttributes? {
    var path = binary
    try {
        // PRoot can encode one hard link as two links into the guest's own .l2s directory.
        repeat(3) { depth ->
            val entry = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (entry.isRegularFile) return entry
            if (!entry.isSymbolicLink || depth == 2 || !Files.isDirectory(store, LinkOption.NOFOLLOW_LINKS)) return null
            val target = Files.readSymbolicLink(path)
            val name = target.fileName ?: return null
            if (!name.toString().startsWith(".l2s.")) return null
            val owned = store.resolve(name)
            if (target != owned && target != path.parent.relativize(owned)) return null
            path = owned
        }
    } catch (_: NoSuchFileException) {
        return null
    }
    return null
}

/** Short, process-local admission reuse. Callers serialize through the Ubuntu mutation gate. */
internal class UbuntuGuestReadinessCache(private val nanoTime: () -> Long = System::nanoTime) {
    private var seal: String? = null
    private var admittedAt = 0L

    fun mayReuse(currentSeal: String?): Boolean {
        val age = nanoTime() - admittedAt
        return currentSeal != null && currentSeal == seal && age in 0 until MAX_AGE_NANOS
    }

    fun admit(currentSeal: String?) {
        seal = currentSeal
        admittedAt = nanoTime()
    }

    fun invalidate() { seal = null }

    companion object {
        const val MAX_AGE_NANOS = 30_000_000_000L

        /** An interrupted or unrecognized guest must pass full admission. */
        fun fingerprint(guest: File, engine: File, expectedLauncher: String, recoveryPaths: List<File>, retainedPrevious: File? = null): String? = runCatching {
            val noFollow = LinkOption.NOFOLLOW_LINKS
            require(recoveryPaths.none { Files.exists(it.toPath(), noFollow) })
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(value: String) { digest.update((value + "\u0000").toByteArray(Charsets.UTF_8)) }
            add(guest.canonicalPath)
            add(expectedLauncher)
            val directories = listOf(guest.parentFile, guest, File(guest, "rootfs"), File(guest, "rootfs/usr"), File(guest, "rootfs/usr/bin"))
            val binaries = listOf(File(guest, "rootfs/usr/bin/apt-get"), File(guest, "rootfs/usr/bin/dpkg"), File(guest, "rootfs/usr/bin/bash"))
            val linkStore = guest.toPath().resolve("rootfs/.l2s")
            for (file in directories + engine + binaries) {
                val attrs = if (file in binaries) ubuntuBinaryAttributes(file.toPath(), linkStore) ?: return null
                    else Files.readAttributes(file.toPath(), PosixFileAttributes::class.java, noFollow)
                require(!attrs.isSymbolicLink && if (file in directories) attrs.isDirectory else attrs.isRegularFile)
                add(file.path)
                // Bind directory admission to stable identity and mode, not child metadata changed by installation.
                add(if (file in directories && attrs.fileKey() != null) "${attrs.fileKey()}"
                    else "${attrs.fileKey()}:${attrs.creationTime()}:${attrs.lastModifiedTime()}:${attrs.size()}")
                add(attrs.permissions().toString())
            }
            // A retained backup is not an active transaction while the current root exists. The bootstrap
            // reconciler never restores it over that root.
            retainedPrevious?.let { previous ->
                add(previous.path)
                if (!Files.exists(previous.toPath(), noFollow)) add("absent")
                else {
                    val attrs = Files.readAttributes(previous.toPath(), BasicFileAttributes::class.java, noFollow)
                    require(attrs.isDirectory && !attrs.isSymbolicLink)
                    add(if (attrs.fileKey() != null) "${attrs.fileKey()}"
                        else "${attrs.creationTime()}:${attrs.lastModifiedTime()}:${attrs.size()}")
                    add(Files.getPosixFilePermissions(previous.toPath(), noFollow).toString())
                }
            }
            for (file in listOf(File(guest, "launch"), File(guest, "BOOTSTRAP_LOCK.txt"))) {
                val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, noFollow)
                require(before.isRegularFile && !before.isSymbolicLink && before.size() in 1..65_536)
                val bytes = file.inputStream().use { input ->
                    val buffer = ByteArray(65_537)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    buffer.copyOf(count)
                }
                require(bytes.size in 1..65_536)
                val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, noFollow)
                require(before.fileKey() == after.fileKey() && before.lastModifiedTime() == after.lastModifiedTime() && before.size() == after.size())
                add(file.path)
                add("${before.fileKey()}:${before.creationTime()}:${before.lastModifiedTime()}:${bytes.size}")
                add(Files.getPosixFilePermissions(file.toPath(), noFollow).toString())
                digest.update(bytes)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
