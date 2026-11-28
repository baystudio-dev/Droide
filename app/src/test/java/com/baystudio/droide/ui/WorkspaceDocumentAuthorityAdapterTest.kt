package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import com.baystudio.droide.core.WorkspaceDocumentKind
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceDocumentAuthorityAdapterTest {
    private lateinit var root: java.io.File
    private lateinit var files: FileRepository

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-document-authority-").toFile()
        root.resolve("Main.kt").writeText("fun main() = Unit\n")
        files = FileRepository(root)
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun reconcilePreservesEvictedTabsWhileStillDetectingDeletions() = runBlocking {
        val state = EditorWorkspaceState()
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files)
        repeat(32) { index ->
            val path = "f$index.txt"
            root.resolve(path).writeText("text $index")
            state.document(path).ensureLoaded(files)
            state.protectActiveEditor(path)
            state.trimInactiveCleanDocuments()
        }
        assertEquals(1, state.allDocuments().count { it.loaded })
        root.resolve("f0.txt").writeText("new while inactive")
        assertTrue(root.resolve("f1.txt").delete())
        assertEquals(listOf("f1.txt"), authority.reconcilePersistedWorkspace().removed)
        assertEquals(1, state.allDocuments().count { it.loaded })
        val reopened = state.document("f0.txt")
        reopened.ensureLoaded(files)
        assertEquals("new while inactive", reopened.content)
    }

    @Test fun openingTab33RetainsLocallySavedTabUntilSafRetrySucceeds() = runBlocking {
        var offline = true
        var published = 0
        val repo = FileRepository(root, onMutation = {
            if (it is FileRepository.Mutation.Sync || it is FileRepository.Mutation.Write) {
                published++
                if (offline) error("provider offline")
            }
        })
        val state = EditorWorkspaceState()
        val tabs = (0 until 32).map { "t$it.txt" }
        val first = state.document(tabs.first())
        root.resolve(first.path).writeText("base")
        first.ensureLoaded(repo)
        first.capture("saved locally")
        first.save(repo)
        assertFalse(first.dirty)
        assertTrue(first.saveSynchronizationPending)
        val next = requireNotNull(EditorTabRetention.open(tabs, "extra.txt", tabs.last()) {
            state.peek(it)?.hasPendingTabWork == true
        })
        EditorTabRetention.closeEvicted(tabs, next, state) {}
        assertSame(first, state.peek(first.path))
        val before = published
        offline = false
        first.save(repo).getOrThrow()
        assertEquals(before + 1, published)
        assertFalse(first.saveSynchronizationPending)
    }

    @Test fun unchangedEditableFilesKeepTheirRevisionAndDirtyBuffersAcrossTheToolSizeLimit() = runBlocking {
        for (size in listOf(2_000_000, 2_000_001, 3_000_000)) {
            val target = root.resolve("sample-$size.txt")
            val original = "a".repeat(size)
            target.writeText(original)
            val state = EditorWorkspaceState()
            val document = state.document(target.name)
            document.ensureLoaded(files)
            val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files)
            val revision = document.revision
            val sync = FileRepository.Mutation.Sync(target.name)
            repeat(3) { assertFalse(authority.persistedMutation(sync).changed) }
            assertEquals(revision, document.revision)
            val external = "z" + original.drop(1)
            target.writeText(external)
            assertEquals(listOf(target.name), authority.persistedMutation(sync).refreshed)
            assertEquals(external, document.content)
            val draft = "b" + original.drop(1)
            document.capture(draft)
            assertTrue(authority.persistedMutation(sync).conflicts.isEmpty())
            target.writeText("c" + original.drop(1))
            assertEquals(listOf(target.name), authority.reconcilePersistedWorkspace().conflicts)
            assertEquals(draft, document.content)
            assertTrue(document.dirty)
        }
    }

    @Test fun temporaryPermissionLossPreservesBothCleanAndDirtyTabsUntilAbsenceIsProven() = runBlocking {
        val folder = root.resolve("folder").apply { mkdir() }
        val permissions = Files.getPosixFilePermissions(folder.toPath())
        val target = folder.resolve("note.txt").apply { writeText("hello") }
        val path = "folder/note.txt"
        val state = EditorWorkspaceState()
        val document = state.document(path)
        document.ensureLoaded(files)
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined, files)
        val sync = FileRepository.Mutation.Sync(path)
        for (dirty in listOf(false, true)) {
            if (dirty) document.capture("draft")
            try {
                Files.setPosixFilePermissions(folder.toPath(), emptySet())
                org.junit.Assume.assumeFalse("Needs an unprivileged POSIX user", Files.isReadable(target.toPath()))
                val failure = runCatching { authority.persistedMutation(sync) }.exceptionOrNull()
                assertTrue(failure.toString(), failure is java.io.IOException)
                assertSame(document, state.peek(path))
                assertEquals(dirty, document.dirty)
            } finally { Files.setPosixFilePermissions(folder.toPath(), permissions) }
            assertFalse(authority.persistedMutation(sync).changed)
            assertEquals("hello", target.readText())
        }
        document.reload(files, discardUnsaved = true)
        Files.delete(target.toPath())
        assertEquals(listOf(path), authority.persistedMutation(sync).removed)
    }

    @Test fun snapshotsReflectLiveUnsavedEditorBufferWithoutSavingDisk() = runBlocking {
        val editorState = EditorWorkspaceState()
        val document = editorState.document("Main.kt")
        document.ensureLoaded(files)
        val authority = EditorWorkspaceDocumentAuthority(editorState, Dispatchers.Unconfined)

        val clean = requireNotNull(authority.snapshot("Main.kt"))
        assertEquals("fun main() = Unit\n", clean.content)
        assertFalse(clean.dirty)
        assertEquals(WorkspaceDocumentKind.TEXT, clean.kind)

        assertTrue(document.captureInsert(0, "// unsaved\n"))
        document.captureSelection(3, 8)
        val dirty = requireNotNull(authority.snapshot("Main.kt"))

        assertEquals("// unsaved\nfun main() = Unit\n", dirty.content)
        assertTrue(dirty.dirty)
        assertTrue(dirty.changeVersion > clean.changeVersion)
        assertEquals(3, dirty.selectionStart)
        assertEquals(8, dirty.selectionEnd)
        assertEquals("fun main() = Unit\n", root.resolve("Main.kt").readText())
    }

    @Test fun dirtySnapshotsOnlyReturnDirtyLiveDocuments() = runBlocking {
        val editorState = EditorWorkspaceState()
        val main = editorState.document("Main.kt")
        main.ensureLoaded(files)
        root.resolve("Other.kt").writeText("val x = 1\n")
        val other = editorState.document("Other.kt")
        other.ensureLoaded(files)
        other.capture("val x = 2\n")

        val authority = EditorWorkspaceDocumentAuthority(editorState, Dispatchers.Unconfined)
        assertEquals(listOf("Other.kt"), authority.dirtySnapshots().map { it.path })
    }
    @Test fun recoveryObservationRejectsDetachAndSameEditorRebind() = runBlocking {
        val state = EditorWorkspaceState()
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val bridge = com.baystudio.droide.core.WorkspaceDocumentAuthorityBridge()
        val binding = bridge.bind(authority)
        val observed = bridge.observeRecovery("Main.kt")
        assertFalse(observed.canCommit)
        binding.close()
        assertTrue(runCatching { observed.validate() }.isFailure)
        bridge.bind(authority)
        assertTrue(runCatching { observed.validate() }.isFailure)
        bridge.observeRecovery("Main.kt").validate()
    }

    @Test fun recoveryObservationRejectsCleanEditUndoAndNewOverlappingDocument() = runBlocking {
        val state = EditorWorkspaceState()
        val document = state.document("Main.kt").also { it.ensureLoaded(files) }
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val observed = authority.observeRecovery("Main.kt")
        val content = document.content
        document.capture("temporary edit")
        document.capture(content)
        assertFalse(document.dirty)
        assertTrue(runCatching { observed.validate() }.isFailure)
        val missing = authority.observeRecovery("folder")
        state.document("folder/new.kt")
        assertTrue(runCatching { missing.validate() }.isFailure)
    }

    @Test fun recoveryObservationReportsPendingWorkAndNeverRunsMutationClosure() = runBlocking {
        val state = EditorWorkspaceState()
        val document = state.document("Main.kt").also { it.ensureLoaded(files) }
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val clean = authority.observeRecovery("Main.kt")
        document.markSaveNotificationPending()
        assertTrue(runCatching { clean.validate() }.isFailure)
        val pending = authority.observeRecovery("Main.kt")
        assertEquals(setOf("Main.kt"), pending.pendingPaths)
        var committed = false
        assertTrue(runCatching { pending.commitClean { committed = true } }.isFailure)
        assertFalse(committed)
    }

    @Test fun recoveryObservationCancellationDoesNotRunMutation() = runBlocking {
        val state = EditorWorkspaceState()
        val observed = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined).observeRecovery("Main.kt")
        var committed = false
        assertTrue(runCatching { observed.commitClean { committed = true } }.isFailure)
        val cancelled = kotlinx.coroutines.Job().apply { cancel() }
        val failure = runCatching { kotlinx.coroutines.withContext(cancelled) { observed.validate() } }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertFalse(committed)
    }

    private fun nativeBinding(owner: Any = Any()) = EditorNativeHandoffBinding(owner,
        begin = { error("Observation must not begin native handoff") }, requireReady = {})

    @Test fun recoveryObservationDetectsNativeBindingAbaWithoutCapturingInput() = runBlocking {
        val state = EditorWorkspaceState()
        val bridge = ActiveEditorBridge()
        var captures = 0
        bridge.snapshot = { captures++ }
        val native = nativeBinding()
        bridge.nativeHandoff = native
        state.bindProjectHandoffCapture(bridge::captureForSave, { bridge.nativeHandoff }, { bridge.recoveryLifecycleGeneration })
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val observed = authority.observeRecovery("Main.kt")
        observed.validate()
        val generation = bridge.recoveryLifecycleGeneration
        bridge.nativeHandoff = native // Idempotent assignment is not a lifecycle transition.
        observed.validate()
        assertEquals(generation, bridge.recoveryLifecycleGeneration)
        bridge.nativeHandoff = null
        bridge.nativeHandoff = native
        assertTrue(runCatching { observed.validate() }.isFailure)
        assertEquals(0, captures)
        assertFalse(observed.canCommit)
    }

    @Test fun staleCaptureCloseDoesNotInvalidateNewOwnerButCurrentCloseDoes() = runBlocking {
        val state = EditorWorkspaceState()
        val first = state.bindProjectHandoffCapture({})
        val second = state.bindProjectHandoffCapture({})
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val observed = authority.observeRecovery("Main.kt")
        first.close()
        observed.validate()
        second.close()
        assertTrue(runCatching { observed.validate() }.isFailure)
        val after = authority.observeRecovery("Main.kt")
        second.close() // Exact-owner close is idempotent.
        after.validate()
    }

    @Test fun hiddenRecoveryConflictInvalidatesEmptyDocumentObservation() = runBlocking {
        val state = EditorWorkspaceState()
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val observed = authority.observeRecovery("Main.kt")
        assertTrue(state.allDocuments().isEmpty())
        state.retainRecoveryConflict("Main.kt", "late recovered text", null)
        assertTrue(runCatching { observed.validate() }.isFailure)
        val next = authority.observeRecovery("Main.kt")
        assertEquals(setOf("Main.kt"), next.pendingPaths)
        next.validate()
        assertEquals("late recovered text", state.recoverySnapshot(emptyList(), "")!!.conflictBuffers["Main.kt"])
        assertTrue(state.allDocuments().isEmpty())
    }

    @Test fun deferredRecoveryOwnershipIsObservedWithoutReadingItsBytes() = runBlocking {
        val state = EditorWorkspaceState()
        var reads = 0
        val deferred = com.baystudio.droide.core.EditorRecoveryBuffer("folder/child.kt", "owned-blob", "ab".repeat(32), Any(),
            loader = { reads++; "preserved bytes" })
        state.retainRecoveryConflict("folder/child.kt", null, deferred)
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val observed = authority.observeRecovery("folder")
        assertEquals(setOf("folder/child.kt"), observed.pendingPaths)
        observed.validate(); observed.validate()
        assertEquals(0, reads)
        assertTrue(state.recoverySnapshot(emptyList(), "")!!.deferredConflictBuffers["folder/child.kt"] === deferred)
        assertFalse(observed.canCommit)
    }

    @Test fun completedRecoveryAndRetirementAttemptsInvalidateEarlierObservation() = runBlocking {
        val state = EditorWorkspaceState()
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val beforeRecovery = authority.observeRecovery("Main.kt")
        val recovery = state.beginRecovery()
        state.cancelRecovery(recovery)
        assertTrue(runCatching { beforeRecovery.validate() }.isFailure)
        val beforeInitialized = authority.observeRecovery("Main.kt")
        state.completeRecovery()
        assertTrue(runCatching { beforeInitialized.validate() }.isFailure)
        val beforeRetirement = authority.observeRecovery("Main.kt")
        val owner = Any()
        state.sealForProjectHandoff(owner)
        state.abortProjectHandoff(owner)
        assertTrue(runCatching { beforeRetirement.validate() }.isFailure)
        authority.observeRecovery("Main.kt").validate()
    }

    @Test fun lateNativeTextIsPreservedAndInvalidatesRecoveryObservation() = runBlocking {
        val state = EditorWorkspaceState()
        val document = state.document("Main.kt").also { it.ensureLoaded(files) }
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val observed = authority.observeRecovery("Main.kt")
        // This is the real full-buffer capture path used by release/late-native callbacks.
        document.captureNativeText("late native bytes")
        assertTrue(runCatching { observed.validate() }.isFailure)
        assertEquals("late native bytes", document.content)
        assertTrue(document.dirty)
        assertEquals("fun main() = Unit\n", root.resolve("Main.kt").readText())
    }

    @Test fun clearingCaptureCallbackInvalidatesObservationEvenWithSameNativeOwner() = runBlocking {
        val state = EditorWorkspaceState()
        val bridge = ActiveEditorBridge()
        val callback: () -> Unit = { error("Read-only observation must not capture") }
        bridge.snapshot = callback
        bridge.nativeHandoff = nativeBinding()
        state.bindProjectHandoffCapture(bridge::captureForSave, { bridge.nativeHandoff }, { bridge.recoveryLifecycleGeneration })
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val observed = authority.observeRecovery("Main.kt")
        val generation = bridge.recoveryLifecycleGeneration
        bridge.snapshot = callback
        observed.validate()
        assertEquals(generation, bridge.recoveryLifecycleGeneration)
        bridge.clear()
        assertTrue(runCatching { observed.validate() }.isFailure)
    }

    @Test fun unchangedNativeBindingThatBecomesUnavailableRejectsObservation() = runBlocking {
        val state = EditorWorkspaceState()
        var ready = true
        var begins = 0
        var captures = 0
        val native = EditorNativeHandoffBinding(Any(), begin = {
            begins++; error("Observation must not begin native handoff")
        }, requireReady = { check(ready) { "Native editor released" } })
        state.bindProjectHandoffCapture({ captures++ }, { native }, { 1L })
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val observed = authority.observeRecovery("Main.kt")
        ready = false
        assertTrue(runCatching { observed.validate() }.isFailure)
        assertTrue(runCatching { authority.observeRecovery("Main.kt") }.isFailure)
        assertEquals(0, begins); assertEquals(0, captures)
        assertFalse(observed.canCommit)
    }

}
