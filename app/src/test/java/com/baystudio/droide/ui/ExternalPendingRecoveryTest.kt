package com.baystudio.droide.ui

import android.content.Context
import android.content.ContextWrapper
import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExternalPendingRecoveryTest {
    private val spec = WorkspaceAgentPluginCatalog.entries.first()

    private class Backend : WorkspaceAgentChatBackend {
        val calls = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode, resumeSessionId: String?,
                                     onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            calls += prompt
            onEvent(WorkspaceAgentEvent.SessionBound("native"))
            started.complete(Unit)
            complete.await()
            return WorkspaceAgentTurnResult(agentId, "native", "done", "end_turn", emptyList(), emptyList())
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit) =
            WorkspaceAgentEvent.Configuration("native", WorkspaceAgentSessionConfiguration())
        override suspend fun selectModel(agentId: String, expectedSessionId: String, modelId: String) = error("unused")
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String = error("unused")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    private suspend fun ExternalAgentSessionController.send(backend: Backend, text: String) =
        submitDurably(backend, spec, text, WorkspaceAgentMode.EDIT, AgentPromptDelivery.QUEUE, emptyList(), null) { true }

    private class Storage(val root: File) {
        var writable = true
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        // Cipher/directory synchronization are seams; the archived records, flow and SQLite history are real.
        val archive = ExitDraftStore(context, "sample", object : ExitDraftCipher {
            override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
                check(writable) { "Fixture storage unavailable" }
                return plain.copyOf()
            }
            override fun decrypt(encrypted: ByteArray, aad: ByteArray) = encrypted.copyOf()
        }) {}
        val journal = archive.named("pending")
    }

    private fun fixture(block: suspend CoroutineScope.(ExternalAgentSessionController, Backend, Storage) -> Unit) = runBlocking {
        withTimeout(15_000) {
            val temp = Files.createTempDirectory("external-pending-recovery").toFile()
            val storage = Storage(temp)
            val history = SqlitePluginConversationHistory(temp)
            val owner = SupervisorJob(coroutineContext[Job])
            val controller = ExternalAgentSessionController(CoroutineScope(coroutineContext + owner), history = history,
                workDir = temp, workAdmission = { true }).also { it.pendingStore = storage.journal }
            val backend = Backend()
            try {
                assertTrue(controller.recoverPendingInputs(storage.archive).isEmpty())
                assertTrue(controller.send(backend, "first"))
                backend.started.await()
                while (controller[spec.id].sessionId != "native") yield()
                block(controller, backend, storage)
            } finally {
                controller.close()
                owner.cancelAndJoin()
                history.close()
                PathSecurity.deleteTreeNoFollow(temp)
            }
        }
    }

    @Test fun restartRecoversBothIdenticalInputsAsSeparateManualDraftsWithoutReplay() = fixture { controller, backend, storage ->
        assertTrue(controller.send(backend, "second"))
        assertTrue(controller.send(backend, "second"))
        val entries = storage.journal.slots()
        assertEquals(2, entries.size)
        // Simulate process loss after archive publication but before clearing the journal.
        storage.archive.preserve(listOf(controller.pendingPrompts.first().asRecoveryRecord()))
        controller.close()
        val restored = ExternalAgentSessionController(this).also { it.pendingStore = storage.archive.named("pending") }
        val copies = restored.recoverPendingInputs(storage.archive)
        assertEquals(listOf("second", "second"), copies.map { it.text })
        assertEquals(entries.keys, copies.mapNotNull { it.slot }.toSet())
        assertTrue(storage.journal.slots().isEmpty())
        assertTrue(restored.pendingPrompts.isEmpty())
        assertEquals(listOf("first"), backend.calls)
    }

    @Test fun enqueueFailureKeepsQueueEmptyAndNormalPromotionRetiresExactlyOneReceipt() = fixture { controller, backend, storage ->
        storage.writable = false
        assertTrue(runCatching { controller.send(backend, "rejected") }.isFailure)
        assertTrue(controller.pendingPrompts.isEmpty())
        storage.writable = true
        assertTrue(controller.send(backend, "second"))
        assertEquals(listOf("second"), storage.journal.slots().values.map { it.text })
        backend.complete.complete(Unit)
        while (controller.busy) delay(1)
        assertEquals(listOf("first", "second"), backend.calls)
        assertTrue(storage.journal.slots().isEmpty())
        assertTrue(storage.archive.load().isEmpty())
    }

    @Test fun archiveAtCapacityCanRecoverAfterOneCopyIsRemovedWithoutResendingAnything() = fixture { controller, backend, storage ->
        assertTrue(controller.send(backend, "retained"))
        controller.close()
        val old = (1..ExitDraftCodec.MAX_RECORDS).map { ExitDraftRecord("other", null, "old $it") }
        storage.archive.preserve(old)
        val restarted = ExternalAgentSessionController(this).also { it.pendingStore = storage.journal }
        assertTrue(runCatching { restarted.recoverPendingInputs(storage.archive) }.isFailure)
        assertEquals(listOf("retained"), storage.journal.slots().values.map { it.text })
        storage.archive.remove(old.first().identity)
        var published = emptyList<ExitDraftRecord>()
        restarted.pendingRecoveryPublished = { published = it }
        assertFalse(restarted.submitDurably(backend, spec, "no new input", WorkspaceAgentMode.EDIT,
            AgentPromptDelivery.QUEUE, emptyList(), null) { false })
        assertEquals(1, published.count { it.text == "retained" })
        assertTrue(storage.journal.slots().isEmpty())
        assertEquals(listOf("first"), backend.calls)
    }

    @Test fun explicitRemovalRetiresTheRecoveryReceiptBeforeDroppingInput() = fixture { controller, backend, storage ->
        assertTrue(controller.send(backend, "remove"))
        controller.removePendingInput(controller.pendingPrompts.single().input.id)
        while (controller.pendingPrompts.isNotEmpty()) delay(1)
        assertTrue(storage.journal.slots().isEmpty())
        assertEquals(listOf("first"), backend.calls)
    }
}
