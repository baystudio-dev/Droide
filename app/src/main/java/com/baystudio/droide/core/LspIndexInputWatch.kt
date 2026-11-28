package com.baystudio.droide.core

import android.os.FileObserver
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Hints for local disk-indexed servers that lack an implementation of dynamic file watching.
 * Only the module and open-document directories are observed. Retirement never starts a process. */
internal class LspIndexInputWatch(private val spec: LanguageServerSpec, private val root: File,
    private val changed: () -> Unit,
    private val subscribe: (File, (Int, String?) -> Unit) -> AutoCloseable? = WorkspaceDirectoryEvents::subscribe,
) : AutoCloseable {
    private val subscriptions = mutableMapOf<File, AutoCloseable>()
    private val invalidated = AtomicBoolean(false)
    val dirty get() = invalidated.get()
    @Volatile private var closed = false

    @Synchronized fun watch(directory: File) {
        if (closed || dirty || directory in subscriptions || subscriptions.size >= 32 ||
            !PathSecurity.contains(root, directory)) return
        val watched = try { subscribe(directory) { event, name ->
            val affectsIndex = event and (FileObserver.MOVE_SELF or FileObserver.DELETE_SELF) != 0 ||
                event and (WorkspaceDirectoryEvents.STRUCTURE or FileObserver.CLOSE_WRITE) != 0 &&
                name != null && relevant(name)
            if (!closed && affectsIndex && invalidated.compareAndSet(false, true)) changed()
        } } catch (_: Exception) { null }
        if (watched != null) subscriptions[directory] = watched
    }

    private fun relevant(name: String): Boolean =
        name.substringAfterLast('.', "") in spec.extensions || name in spec.filenames || when (spec.id) {
            "terraform-ls" -> name.endsWith(".tf.json") || name.endsWith(".tfvars.json") || name == ".terraform.lock.hcl"
            "buf" -> name in setOf("buf.yaml", "buf.work.yaml", "buf.lock")
            "zls" -> name == ".zigversion" || name == "zls.json"
            "lemminx" -> name.endsWith(".xsd") || name.endsWith(".dtd") || name.endsWith(".xsl")
            else -> false
        }

    @Synchronized override fun close() {
        closed = true
        subscriptions.values.forEach { it.close() }
        subscriptions.clear()
    }
}
