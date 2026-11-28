package com.baystudio.droide.ui

import android.os.FileObserver
import com.baystudio.droide.core.WorkspaceDirectoryEvents
import com.baystudio.droide.core.ExplorerFilePolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Nonrecursive watches along the active path; ancestor bindings only accept structural events. */
internal class AndroidWorkspaceDocumentWatch(
    private val root: File,
    private val subscribe: (File, (Int, String?) -> Unit) -> AutoCloseable? = WorkspaceDirectoryEvents::subscribe,
) : WorkspaceDocumentWatchFactory {
    internal data class Target(val directory: File, val child: String, val watchingAncestor: Boolean)

    override fun open(path: String, invalidate: (Boolean) -> Unit): AutoCloseable? {
        val target = target(root, path) ?: return null
        val subscriptions = mutableListOf<AutoCloseable>()
        val base = root.canonicalFile
        var current = target
        try {
            while (true) {
                val bound = current
                val mask = if (bound.watchingAncestor) WorkspaceDirectoryEvents.STRUCTURE else EVENTS
                subscribe(bound.directory) { event, child ->
                    val selfChanged = event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0
                    if (event and mask != 0 && (selfChanged || child == bound.child)) {
                        invalidate(selfChanged || bound.watchingAncestor)
                    }
                }?.let(subscriptions::add)
                if (bound.directory == base) break
                current = if (subscriptions.size >= 31)
                    Target(base, path.substringBefore('/'), true)
                else Target(bound.directory.parentFile ?: break, bound.directory.name, true)
            }
        } catch (failure: Throwable) {
            subscriptions.forEach { it.close() }
            throw failure
        }
        return AutoCloseable { subscriptions.forEach { it.close() } }
    }

    internal companion object {
        private const val EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF

        fun target(root: File, path: String): Target? {
            require(path.isNotBlank() && path.length <= 4_096) { "Invalid active document watch path" }
            require(!Files.isSymbolicLink(root.toPath())) { "Workspace root cannot be a symbolic link" }
            require(ExplorerFilePolicy.requireUnaliasedProjectPath(root, path) == path) {
                "Active document watch path must be unaliased"
            }
            val base = root.canonicalFile
            if (!Files.isDirectory(base.toPath(), NOFOLLOW_LINKS)) return null
            var child = File(base, path)
            val intendedParent = child.parentFile ?: return null
            var directory = intendedParent
            while (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
                if (directory == base) return null
                child = directory
                directory = directory.parentFile ?: return null
            }
            require(directory.toPath().startsWith(base.toPath())) { "Active document watch escaped workspace" }
            return Target(directory, child.name, directory != intendedParent)
        }
    }
}
