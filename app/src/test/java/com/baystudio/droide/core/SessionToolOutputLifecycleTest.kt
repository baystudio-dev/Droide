package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SessionToolOutputLifecycleTest {
    private fun fixture(body: suspend (File) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("droide-output-lifecycle-").toFile()
        try { body(directory.resolve("projects/test").apply { mkdirs() }) }
        finally { PathSecurity.deleteTreeNoFollow(directory) }
    }

    @Test fun deletedSessionReleasesTheDefaultQuotaAndRejectsLateToolResults() = fixture { project ->
        val sessions = SessionManager(project)
        val old = sessions.create("Old")
        val store = AgentToolOutputStore.forWorkspace(project)
        val output = "x".repeat(12_100)
        repeat(512) { sessions.retainToolOutput(old.id, "$it$output") }
        assertTrue(runCatching { sessions.retainToolOutput(old.id, output) }.isFailure)
        sessions.delete(old.id)
        assertNull(sessions.get(old.id))
        assertTrue(runCatching { sessions.retainToolOutput(old.id, output) }.isFailure)
        val next = sessions.create("New")
        val retained = sessions.retainToolOutput(next.id, output)
        assertTrue(store.read(next.id, retained.reference, 8_000).endsWith(output.substring(8_000)))
    }

    @Test fun legacyQuotaRecoveryProtectsLiveSessionsAndAnUnpublishedFork() = fixture { project ->
        val directory = project.resolve(".droide/sessions")
        val store = AgentToolOutputStore(project.parentFile.resolve("outputs"), AgentToolOutputStore.Limits(4096, 2, 1024), directory)
        val sessions = SessionManager(project).also { it.toolOutputStore = { store } }
        val old = sessions.create()
        val parent = sessions.create()
        val oldRef = sessions.retainToolOutput(old.id, "old").reference
        val shared = sessions.retainToolOutput(parent.id, "shared").reference
        Files.delete(directory.resolve("${old.id}.json").toPath())
        val childId = "s-unpublished-fixture"
        store.publishFork(parent.id, childId, setOf(shared)) {
            sessions.retainToolOutput(parent.id, "replacement")
            assertTrue(store.read(childId, shared).endsWith("shared"))
            assertTrue(store.read(parent.id, shared).endsWith("shared"))
        }
        assertFalse(store.owns(old.id, oldRef))
        sessions.delete(parent.id)
        assertTrue(store.read(childId, shared).endsWith("shared"))
    }

    @Test fun deletionWaitsForAnAdmittedWriterAndCleansItsLastOutput() = fixture { project ->
        val sessions = SessionManager(project)
        val session = sessions.create()
        val store = AgentToolOutputStore.forWorkspace(project)
        val admitted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        kotlinx.coroutines.coroutineScope {
            val writer = async(Dispatchers.IO) {
                store.retain(session.id, "last") {
                    admitted.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                    true
                }
            }
            try {
                assertTrue(admitted.await(10, TimeUnit.SECONDS))
                val deletion = async(Dispatchers.IO) { sessions.delete(session.id) }
                withTimeout(8_000) { while (sessions.get(session.id) != null) yield() }
                resume.countDown()
                val reference = writer.await().reference
                deletion.await()
                assertFalse(store.owns(session.id, reference))
            } finally { resume.countDown() }
        }
    }
}
