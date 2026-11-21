package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExternalAgentWorkingCopyRecoveryTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class Project(val root: File, val scope: CoroutineScope) {
        val source = root.resolve("project").apply { mkdir(); resolve("notes").writeText("saved") }
        val cache = root.resolve("cache").apply { mkdir() }
        val storage = root.resolve("private")
        fun host() = LocalAgentWorkspaceProcessHost.forWorkspace(source, cache, scope, storage)
        fun authority(work: File) = MirrorMetadataAuthority(File(work.parentFile, ".${work.name}-sync-authority"), source, work)
        fun previous(work: File) = authority(work).processCustody(UUID.randomUUID().toString()).acquire(work)
        suspend fun open(host: LocalAgentWorkspaceProcessHost) = File(host.pathMapper().remoteRoot)
        fun repository() = FileRepository(source, newFilePublisher = NewProjectFilePublisher.native())
    }

    private fun project(action: suspend Project.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("cli-recovery").toFile()
        try { Project(root, this).action() } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun runningPreviousWriterCannotChangeReplacementAndUnpublishedFilesCanBeRescued() = project {
        val old = open(host())
        old.resolve("notes").writeText("unpublished")
        previous(old).use { receipt ->
            val child = receipt.withFactory({}) {
                ProcessBuilder("/bin/sh", "-c", "read line && printf '%s' \"\$line\" > late.txt").directory(old).start()
            }
            try {
                val replacement = host()
                val work = open(replacement)
                assertNotEquals(old, work)
                assertEquals("saved", work.resolve("notes").readText())
                child.outputStream.bufferedWriter().use { it.write("older CLI\n") }
                assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, child.exitValue())
                assertFalse(work.resolve("late.txt").exists())
                assertFalse(source.resolve("late.txt").exists())
                assertEquals(1, authority(old).pendingProcessOwners().size)
                authority(work).processCustody().acquire(work).use { it.withFactory({}, {}) }
                assertEquals(work, open(host()))
                assertEquals(work, open(replacement))
                val recovery = replacement.recoveryFor(0)
                recovery.archiveWorkingCopy(repository(), "rescue.tar", cache) {}
                val contents = mutableMapOf<String, String>()
                TarArchiveInputStream(source.resolve("rescue.tar").inputStream()).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isFile) contents[entry.name.substringAfterLast('/')] = tar.readBytes().decodeToString()
                    }
                }
                assertEquals("older CLI", contents["late.txt"])
                assertEquals("unpublished", contents["notes"])
                assertTrue(runCatching { recovery.archiveWorkingCopy(repository(), "rescue.tar", cache) {} }.isFailure)
                assertEquals("saved", source.resolve("notes").readText())
            } finally { child.destroyForcibly(); child.waitFor() }
        }
    }

    @Test fun cleanSameRuntimeAndNeverStartedOwnersKeepTheExistingCopy() = project {
        val work = open(host())
        previous(work).use { assertEquals(work, open(host())) }
        previous(work).use { it.withFactory({}, {}) }
        assertEquals(work, open(host()))
        authority(work).processCustody().acquire(work).use { live ->
            live.withFactory({}, {})
            assertEquals(work, open(host()))
        }
        assertEquals(0, AgentWorkspaceCopies(work).latest())
    }

    @Test fun eachInterruptedGenerationKeepsItsOwnChangesAndRecoveryIdentity() = project {
        val first = open(host())
        previous(first).use {
            it.withFactory({}, {})
            val second = open(host())
            second.resolve("draft").writeText("second")
            previous(second).use { next ->
                next.withFactory({}, {})
                val reopened = host()
                val third = open(reopened)
                assertEquals(2, reopened.activeCopy)
                assertNotEquals(second, third)
                assertEquals("second", second.resolve("draft").readText())
                assertFalse(third.resolve("draft").exists())
                assertNotSame(reopened.recoveryFor(0), reopened.recoveryFor(1))
                assertTrue(runCatching { reopened.recoveryFor(2) }.isFailure)
                assertTrue(runCatching { reopened.recoveryFor(-1) }.isFailure)
            }
        }
    }

    @Test fun earlierFilesRemainReachableWhenProviderPreparationFails() = project {
        val initial = open(host())
        previous(initial).use { old ->
            old.withFactory({}, {})
            val local = host()
            val panel = WorkspaceAgentPluginHost(source, cache, repository(), local, ApprovalManager(),
                PermissionEngine(), scope, local.workspaceIo)
            try {
                assertTrue(runCatching { panel.prepareSession("not-installed") {} }.isFailure)
                val status = panel.workspaceSyncState.value
                assertEquals(1, status.earlierCopies)
                assertEquals(open(local).absolutePath, status.executionRoot)
                panel.archiveEarlierWorkspace(0, "previous.tar")
                assertTrue(source.resolve("previous.tar").length() > 0)
            } finally { panel.closeAndAwait() }
        }
    }

    @Test fun filesystemRecoveryErrorKeepsOriginalRoute() = project {
        val work = open(host())
        authority(work).prepare("pending", "create", MirrorMetadataAuthority.Node.absent,
            MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry("a".repeat(64), 420)))
        assertTrue(runCatching { open(host()) }.isFailure)
        assertEquals(0, AgentWorkspaceCopies(work).latest())
    }

    @Test fun interruptedEnrollmentAndCompetingAdmissionsNeverPublishAnIncompleteCopy() = project {
        val initial = open(host())
        val copies = AgentWorkspaceCopies(initial)
        assertTrue(runCatching { copies.next(0) { error("interrupted enrollment") } }.isFailure)
        assertEquals(0, copies.latest())
        assertFalse(initial.parentFile!!.list()!!.any { it.startsWith(".${initial.name}-copy-") })
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val selected = coroutineScope {
            val first = async {
                copies.next(0) { working ->
                    LocalMirrorAuthorityBootstrap(source, working).prepare()
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val second = async {
                copies.next(0) { working -> LocalMirrorAuthorityBootstrap(source, working).prepare() }
                    .also { release.complete(Unit) }
            }
            awaitAll(first, second)
        }
        assertEquals(listOf(1, 1), selected)
        assertEquals(1, copies.latest())
        assertEquals(copies.working(1), open(host()))
    }

    @Test fun ownerArrivingAfterMappingGetsAFreshTransportRootBeforeAnyPrompt() = project {
        val local = host()
        val initial = open(local)
        previous(initial).use { receipt ->
            receipt.withFactory({}, {})
            val replacement = local.recoverBeforeTransportStart(initial.canonicalPath)
            assertNotEquals(initial.canonicalPath, replacement.remoteRoot)
            assertEquals("saved", File(replacement.remoteRoot, "notes").readText())
            assertEquals(replacement.remoteRoot, local.recoverBeforeTransportStart(initial.canonicalPath).remoteRoot)
            assertEquals(1, AgentWorkspaceCopies(initial).latest())
        }
    }

    @Test fun substitutedRouteCannotRebindArchivedWork() = project {
        val initial = open(host())
        val copies = AgentWorkspaceCopies(initial)
        copies.next(0) { LocalMirrorAuthorityBootstrap(source, it).prepare() }
        val route = File(initial.parentFile, ".${initial.name}-session-1")
        val originalRecord = route.readBytes()
        route.delete()
        Files.createSymbolicLink(route.toPath(), source.resolve("notes").toPath())
        assertTrue(runCatching { open(host()) }.isFailure)
        route.delete()
        route.writeBytes(originalRecord)
        val selected = copies.working(1)
        assertTrue(selected.renameTo(File(selected.parentFile, "original")))
        assertTrue(selected.mkdir())
        assertTrue(runCatching { copies.working(1) }.isFailure)
    }
}
