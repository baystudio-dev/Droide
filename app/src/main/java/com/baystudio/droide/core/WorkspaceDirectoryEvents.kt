package com.baystudio.droide.core

import android.os.FileObserver
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/** Android may return the same inotify watch descriptor to several observers of one inode.
 * Share the native registration, and release it only after the last consumer detaches. */
internal object WorkspaceDirectoryEvents {
    const val STRUCTURE = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
        FileObserver.MOVED_TO or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
    private const val EVENTS = STRUCTURE or FileObserver.CLOSE_WRITE or FileObserver.ATTRIB or FileObserver.MODIFY
    private val entries = mutableMapOf<Any, Registration>()

    @Suppress("DEPRECATION")
    private class Registration(path: String) : FileObserver(path, EVENTS) {
        @Volatile var listeners = emptyList<(Int, String?) -> Unit>()
        override fun onEvent(event: Int, path: String?) {
            for (listener in listeners) try { listener(event, path) } catch (_: Exception) { }
        }
    }

    fun subscribe(directory: File, listener: (Int, String?) -> Unit): AutoCloseable? {
        val attrs = try { Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: java.io.IOException) { return null }
        if (!attrs.isDirectory) return null
        val key = attrs.fileKey() ?: directory.canonicalPath
        synchronized(entries) {
            val existing = entries[key]
            val registration = existing ?: Registration(directory.absolutePath)
            registration.listeners = registration.listeners + listener
            if (existing == null) {
                registration.startWatching()
                entries[key] = registration
            }
            return AutoCloseable {
                synchronized(entries) {
                    if (entries[key] === registration) {
                        registration.listeners = registration.listeners - listener
                        if (registration.listeners.isEmpty()) {
                            entries.remove(key)
                            registration.stopWatching()
                        }
                    }
                }
            }
        }
    }
}
