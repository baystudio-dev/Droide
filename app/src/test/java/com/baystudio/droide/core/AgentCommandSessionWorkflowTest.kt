package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class AgentCommandSessionWorkflowTest {
    private val admissionField = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
    private var previousAdmission: Any? = null

    @Before fun openApplicationWorkFixture() {
        previousAdmission = admissionField.get(null)
        val admission = ApplicationWorkAdmissionState(
            read = { ApplicationWorkAdmissionState.Snapshot("a".repeat(32), true) },
            write = {},
        )
        admission.initialize()
        admissionField.set(null, admission)
    }

    @After fun restoreApplicationWorkFixture() {
        admissionField.set(null, previousAdmission)
    }

    private suspend fun finished(manager: BackgroundCommandManager, id: String) = withTimeout(5_000) {
        while (manager.snapshot(id)?.state == BackgroundCommandManager.State.RUNNING) delay(20)
        requireNotNull(manager.snapshot(id))
    }

    @Test fun fiveEmptyJobPollsDoNotReconcileEditorBuffers() = runBlocking {
        val root = Files.createTempDirectory("droide-empty-polls-").toFile()
        val terminal = AgentWorkflowTerminal()
        val jobs = BackgroundCommandManager(terminal)
        val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a.txt", "saved"))
        var reconciliations = 0
        val authority = object : WorkspaceDocumentAuthority by documents {
            override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation {
                reconciliations++
                return WorkspacePersistedReconciliation()
            }
        }
        try {
            val context = agentWorkflowContext(root, manager = jobs, documents = authority, terminal = terminal)
            repeat(5) {
                assertEquals("No background jobs.", AgentTools.execute("background_job",
                    buildJsonObject { put("action", "list") }, context))
            }
            assertEquals(0, reconciliations)
        } finally { jobs.closeAndJoin(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun completedCommandRetainsItsConflictNoticeAcrossQueriesWithoutRescanning() = runBlocking {
        val root = Files.createTempDirectory("droide-job-reconciliation-").toFile()
        val terminal = AgentWorkflowTerminal()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("draft.txt", "unsaved"))
        val authority = object : WorkspaceDocumentAuthority by docs {
            override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation {
                calls.incrementAndGet()
                return WorkspacePersistedReconciliation(conflicts = listOf("draft.txt"))
            }
        }
        val jobs = BackgroundCommandManager(terminal, onCommandFinished = authority::reconcilePersistedWorkspace)
        try {
            val ctx = agentWorkflowContext(root, manager = jobs, documents = authority, terminal = terminal)
            val result = AgentTools.execute("run_command", buildJsonObject { put("command", "true"); put("yield_time_ms", 1000) }, ctx)
            val job = finished(jobs, jobs.list().single().id)
            assertEquals(0, job.exitCode)
            assertTrue(result, result.contains("draft.txt") || result.contains("Process continues"))
            for (operation in listOf("status", "output", "status")) {
                val notice = AgentTools.execute("background_job", buildJsonObject { put("action", operation); put("job_id", job.id) }, ctx)
                assertTrue(notice, notice.contains("Conflict paths: draft.txt"))
            }
            val poll = AgentTools.execute("write_stdin", buildJsonObject { put("session_id", job.id); put("yield_time_ms", 0) }, ctx)
            assertTrue(poll, poll.contains("Conflict paths: draft.txt"))
            assertEquals(1, calls.get())
        } finally { jobs.closeAndJoin(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun realPipeCommandAcceptsStdinAndReturnsIncrementalUnicodeOutput() = runBlocking {
        val manager = BackgroundCommandManager(AgentWorkflowTerminal())
        try {
            val job = manager.start("printf 'ready\\n'; read line; printf 'reply:%s\\n' \"\$line\"", keepStdinOpen = true)
            val first = manager.awaitOutput(job.id, 0, 3_000)
            assertTrue(first.output.contains("ready")); assertTrue(first.stdinAvailable)
            manager.sendInput(job.id, "日本語 😀\n", closeInput = true)
            val final = finished(manager, job.id)
            val delta = AgentCommandSessions.render(final, first.outputEndCursor)
            assertTrue(delta.contains("reply:日本語 😀")); assertFalse(delta.contains("ready"))
            assertEquals(0, final.exitCode); assertFalse(final.stdinAvailable)
            assertEquals(AgentToolEvidenceState.SUCCEEDED, AgentToolResultSemantics.classify("write_stdin", delta).state)
        } finally { manager.close() }
    }

    @Test fun outputOverflowReportsLostDataAndCursorCanDrainTheTail() = runBlocking {
        val manager = BackgroundCommandManager(AgentWorkflowTerminal())
        try {
            val job = manager.start("head -c 40000 /dev/zero | tr '\\000' x")
            val final = finished(manager, job.id)
            val page = AgentCommandSessions.render(final, 0)
            assertTrue(page.contains("\"output_lost\":true")); assertTrue(page.contains("\"more_output\":true"))
            assertEquals(20_000, final.output.length)
            val metadata = Json.parseToJsonElement(page.lineSequence().first { it.startsWith('{') }).jsonObject
            val next = metadata.getValue("next_output_cursor").jsonPrimitive.long
            assertTrue(next > 0 && next < final.outputEndCursor)
            assertTrue(runCatching { manager.awaitOutput(job.id, final.outputEndCursor + 1, 0) }.isFailure)
        } finally { manager.close() }
    }

    @Test fun managerIdentityPreventsReusingAStaleSessionFromAnotherWorkspaceLifetime() = runBlocking {
        val a = BackgroundCommandManager(AgentWorkflowTerminal()); val b = BackgroundCommandManager(AgentWorkflowTerminal())
        try {
            val first = a.start("printf a"); val second = b.start("printf b")
            assertNotEquals(first.id, second.id)
            assertNull(b.snapshot(first.id))
            finished(a, first.id); finished(b, second.id)
            Unit
        } finally { a.close(); b.close() }
    }

    @Test fun pipeInputLimitsAndCloseAreEnforcedWithoutBlockingProducer() {
        val input = ProcessInputBuffer()
        assertThrows(IllegalArgumentException::class.java) { input.offer("😀".repeat(20_000)) }
        repeat(8) { input.offer("queued") }
        assertThrows(IllegalStateException::class.java) { input.offer("overflow") }
        input.close()
        assertThrows(IllegalStateException::class.java) { input.offer("after close") }
    }

    @Test fun inputRequiresShellEscapePermissionAndReadOnlyModeCannotMutateSessions() = runBlocking {
        val root = Files.createTempDirectory("droide-stdin-policy-").toFile()
        val manager = BackgroundCommandManager(AgentWorkflowTerminal())
        try {
            val job = manager.start("read line; printf '%s' \"\$line\"", keepStdinOpen = true)
            val deny = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("shell_escape", "*", PermEffect.DENY)))
            val args = buildJsonObject { put("session_id", job.id); put("chars", "not approved\n"); put("yield_time_ms", 0) }
            assertTrue(AgentTools.execute("write_stdin", args, agentWorkflowContext(root, manager, permissions = deny)).startsWith("DENIED"))
            assertEquals("", manager.snapshot(job.id)?.output)
            assertTrue(AgentTools.execute("write_stdin", args, agentWorkflowContext(root, manager, mode = AgentMode.REVIEW)).startsWith("DENIED"))
            assertEquals(BackgroundCommandManager.State.KILLED, manager.kill(job.id)?.state)
        } finally { manager.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun execAndResumeDispatchReportRunningExitAndRequestedInterruptionTruthfully() = runBlocking {
        val root = Files.createTempDirectory("droide-exec-dispatch-").toFile()
        val manager = BackgroundCommandManager(AgentWorkflowTerminal())
        try {
            val ctx = agentWorkflowContext(root, manager)
            val output = AgentTools.execute("exec_command", buildJsonObject { put("cmd", "exec sleep 30"); put("yield_time_ms", 0) }, ctx)
            assertEquals(AgentToolEvidenceState.STARTED, AgentToolResultSemantics.classify("exec_command", output).state)
            val id = manager.list().single().id
            val killed = AgentTools.execute("write_stdin", buildJsonObject { put("session_id", id); put("interrupt", true) }, ctx)
            assertEquals(AgentToolEvidenceState.STARTED, AgentToolResultSemantics.classify("write_stdin", killed).state)
            assertNull(AgentToolResultSemantics.classify("write_stdin", killed).success)
            assertTrue(AgentTools.execute("write_stdin", buildJsonObject { put("session_id", id); put("chars", "late\n") }, ctx).startsWith("ERROR"))
        } finally { manager.close(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
