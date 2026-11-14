package com.baystudio.droide.core.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.baystudio.droide.R
import com.baystudio.droide.core.AnchoredWorkspaceFiles
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DroideDocumentsProvider : DocumentsProvider() {
    private val store get() = SharedWorkspaceStore(requireNotNull(context))
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        store.entries().forEach { entry ->
            val row = newRow()
            for (column in columnNames) row.add(column, when (column) {
                Root.COLUMN_ROOT_ID -> entry.id
                Root.COLUMN_DOCUMENT_ID -> SharedDocumentIds.encode(entry.id, "")
                Root.COLUMN_TITLE -> "Droide · ${entry.label}"
                Root.COLUMN_SUMMARY -> "Saved project files · read and copy"
                Root.COLUMN_FLAGS -> Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
                Root.COLUMN_ICON -> R.mipmap.ic_launcher
                Root.COLUMN_MIME_TYPES -> "*/*"
                else -> null
            })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = guarded {
        val (entry, doc) = resolve(documentId)
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { cursor ->
            AnchoredWorkspaceFiles(entry.root).use { append(cursor, entry, doc.relative, it) }
        }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = guarded {
        val (entry, doc) = resolve(parentDocumentId)
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { cursor ->
            AnchoredWorkspaceFiles(entry.root).use { anchor ->
                var visited = 0
                try {
                    anchor.forEachChild(doc.relative) { name ->
                        if (visited++ >= 2000) throw DirectoryLimit()
                        val relative = listOf(doc.relative, name).filter { it.isNotEmpty() }.joinToString("/")
                        if (SharedDocumentIds.visible(relative)) append(cursor, entry, relative, anchor)
                    }
                } catch (_: DirectoryLimit) {
                    cursor.extras = Bundle().apply { putString(android.provider.DocumentsContract.EXTRA_INFO,
                        "Showing the first 2000 entries. Use Droide Explorer to export this large folder.") }
                }
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = guarded {
        resolve(parentDocumentId); resolve(documentId)
        SharedDocumentIds.isChild(parentDocumentId, documentId)
    }

    override fun getDocumentType(documentId: String): String = guarded {
        val (entry, doc) = resolve(documentId)
        AnchoredWorkspaceFiles(entry.root).use { anchor ->
            if (doc.relative.isEmpty() || anchor.kind(doc.relative) == AnchoredWorkspaceFiles.Kind.DIRECTORY)
                Document.MIME_TYPE_DIR else mime(doc.relative)
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = guarded {
        require(mode == "r") { "Projects are shared read-only; edit them in Droide" }
        signal?.throwIfCanceled()
        val (entry, doc) = resolve(documentId)
        check(readers.tryAcquire()) { "Too many project copies are open; try again when one finishes" }
        var input: AnchoredWorkspaceFiles.OpenedFile? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            input = AnchoredWorkspaceFiles(entry.root).use { it.openRead(doc.relative) }
            val original = input.observe()
            pipe = ParcelFileDescriptor.createReliablePipe()
            val ownedInput = requireNotNull(input)
            val writer = pipe[1]
            signal?.setOnCancelListener { runCatching { writer.closeWithError("Copy cancelled") } }
            executor.execute {
                var output: ParcelFileDescriptor.AutoCloseOutputStream? = null
                try {
                    val stream = ParcelFileDescriptor.AutoCloseOutputStream(writer).also { output = it }
                    ownedInput.use { source ->
                        run {
                            val bytes = ByteArray(64 * 1024)
                            var total = 0L
                            var checkedAt = 0L
                            while (true) {
                                signal?.throwIfCanceled()
                                if (System.nanoTime() - checkedAt >= 250_000_000L) {
                                    check(store.entries().any { it == entry }) { "Project sharing was disabled" }
                                    checkedAt = System.nanoTime()
                                }
                                val count = source.read(bytes)
                                if (count < 0) break
                                total += count
                                check(total <= original.attributes.size()) { "Project file changed during copy" }
                                stream.write(bytes, 0, count)
                            }
                            val after = source.observe()
                            check(total == original.attributes.size() && after.changed == original.changed &&
                                after.attributes.lastModifiedTime() == original.attributes.lastModifiedTime()) { "Project file changed; copy it again" }
                        }
                    }
                } catch (failure: Exception) {
                    runCatching { writer.closeWithError(failure.message ?: "Project copy failed") }
                } finally {
                    signal?.setOnCancelListener(null)
                    runCatching { output?.close() }; runCatching { ownedInput.close() }; runCatching { writer.close() }
                    readers.release()
                }
            }
            pipe[0]
        } catch (failure: Exception) {
            runCatching { input?.close() }; pipe?.forEach { runCatching { it.close() } }; readers.release()
            throw failure
        }
    }

    private fun resolve(documentId: String): Pair<SharedWorkspaceStore.Entry, SharedDocumentIds.Document> {
        val document = SharedDocumentIds.decode(documentId)
        val entry = store.entries().singleOrNull { it.id == document.workspaceId }
            ?: throw FileNotFoundException("Project sharing is unavailable or was revoked")
        return entry to document
    }

    private fun append(cursor: MatrixCursor, entry: SharedWorkspaceStore.Entry, relative: String, anchor: AnchoredWorkspaceFiles) {
        val kind = if (relative.isEmpty()) AnchoredWorkspaceFiles.Kind.DIRECTORY else anchor.kind(relative)
        if (kind !in setOf(AnchoredWorkspaceFiles.Kind.FILE, AnchoredWorkspaceFiles.Kind.DIRECTORY)) return
        val metadata = if (kind == AnchoredWorkspaceFiles.Kind.FILE) anchor.openRead(relative).use { it.observe().attributes } else null
        val row = cursor.newRow()
        for (column in cursor.columnNames) row.add(column, when (column) {
            Document.COLUMN_DOCUMENT_ID -> SharedDocumentIds.encode(entry.id, relative)
            Document.COLUMN_DISPLAY_NAME -> if (relative.isEmpty()) entry.label else relative.substringAfterLast('/')
            Document.COLUMN_MIME_TYPE -> if (kind == AnchoredWorkspaceFiles.Kind.DIRECTORY) Document.MIME_TYPE_DIR else mime(relative)
            Document.COLUMN_FLAGS -> 0
            Document.COLUMN_SIZE -> metadata?.size()
            Document.COLUMN_LAST_MODIFIED -> metadata?.lastModifiedTime()?.toMillis()
            else -> null
        })
    }

    private fun mime(path: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    private inline fun <T> guarded(action: () -> T): T = try { action() }
    catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
    catch (failure: Exception) { throw FileNotFoundException(failure.message ?: "Shared project unavailable").apply { initCause(failure) } }

    private class DirectoryLimit : RuntimeException()
    companion object {
        private val readers = Semaphore(2)
        private val executor = (Executors.newFixedThreadPool(2) { task ->
            Thread(task, "shared-project-read").apply { isDaemon = true }
        } as ThreadPoolExecutor).apply { setKeepAliveTime(30, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
