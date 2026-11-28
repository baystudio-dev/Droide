package com.baystudio.droide.ui

import android.os.FileObserver
import com.baystudio.droide.core.WorkspaceDirectoryEvents
import com.baystudio.droide.core.ExplorerFilePolicy
import java.io.File

/** A latency hint for one validated directory. Android does not acknowledge watch coverage. */
internal class AndroidExplorerDirectoryWatch(private val root: File) : ExplorerDirectoryWatchFactory {
    override fun open(path: String, invalidate: (Boolean) -> Unit): AutoCloseable? {
        require(path.length <= 4_096) { "Explorer watch path exceeds its limit" }
        val normalized = ExplorerFilePolicy.requireUserTarget(root, path)
        require(normalized == path) { "Explorer watch path must be unaliased" }
        val directory = if (path.isEmpty()) root else File(root, path)
        if (!directory.isDirectory) return null
        // One observer per directory is nonrecursive. Never inspect/resolve callback child paths.
        return WorkspaceDirectoryEvents.subscribe(directory) { event, _ ->
            if (event and EVENTS != 0) invalidate(event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0)
        }
    }

    private companion object {
        const val EVENTS = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or
            FileObserver.CLOSE_WRITE or FileObserver.ATTRIB or FileObserver.MODIFY
    }
}
