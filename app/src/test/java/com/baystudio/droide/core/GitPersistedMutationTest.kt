package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitPersistedMutationTest {
    @Test fun dirtyEditorBlocksEveryWorktreeMutationAndCleanDiscardReconciles() = runBlocking {
        val root = Files.createTempDirectory("droide-git-mutation-").toFile()
        try {
            val authority = RecordingAuthority()
            var syncedContent: String? = null
            val git = GitManager(root, authority, syncLinkedFolder = {
                syncedContent = root.resolve("README.md").readText()
                "Linked folder synchronized"
            })
            git.init()
            git.configureIdentity("Droide Test", "test@example.com")
            root.resolve("README.md").writeText("base\n")
            git.stage("README.md")
            git.commitStaged("initial")
            root.resolve("README.md").writeText("modified\n")

            authority.dirty = true
            listOf(git.discardWorkingTree("README.md"), git.stash(), git.applyStash(GitStash(0, "0".repeat(40), "pending")), git.pull()).forEach {
                assertTrue(it.contains("Save or discard unsaved editor changes"))
            }
            assertEquals("modified\n", root.resolve("README.md").readText())
            assertEquals(null, syncedContent)

            authority.dirty = false
            assertTrue(git.discardWorkingTree("README.md").contains("Discarded"))
            assertEquals("base\n", root.resolve("README.md").readText())
            assertEquals("base\n", syncedContent)
            assertTrue(authority.reconciled)
        } finally {
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun discardCannotConsumeAChangedWorktreeOrIndexSelection() = runBlocking {
        val root = Files.createTempDirectory("droide-stale-discard-").toFile()
        try {
            val git = GitManager(root)
            git.init()
            git.configureIdentity("Droide Test", "test@example.invalid")
            val file = root.resolve("note.txt")
            file.writeText("base")
            git.stage(file.name)
            git.commitStaged("base")
            file.writeText("edit A")
            val reviewed = git.prepareDiscard(file.name)
            file.writeText("edit B")
            assertTrue(git.discardWorkingTree(reviewed).contains("changed", ignoreCase = true))
            assertEquals("edit B", file.readText())
            val beforeStage = git.prepareDiscard(file.name)
            file.writeText("new index")
            git.stage(file.name)
            git.commitStaged("new baseline")
            file.writeText("edit B")
            assertTrue(git.discardWorkingTree(beforeStage).contains("changed", ignoreCase = true))
            file.writeText("edit C")
            assertTrue(git.discardWorkingTree(git.prepareDiscard(file.name)).contains("Discarded"))
            assertEquals("new index", file.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun failedCommitWithInvalidIdentityLeavesUnstagedEditAlone() = runBlocking {
        val root = Files.createTempDirectory("droide-git-identity-").toFile()
        try {
            val git = GitManager(root)
            git.init()
            git.configureIdentity("Test", "test@example.invalid")
            val file = root.resolve("file.txt")
            file.writeText("base")
            git.stage(file.name)
            git.commitStaged("base")
            org.eclipse.jgit.api.Git.open(root).use { repository ->
                repository.repository.config.apply { setString("user", null, "email", "invalid"); save() }
            }
            file.writeText("draft")
            assertTrue(runCatching { git.commitAll("agent request") }.isFailure)
            assertFalse(git.changes().any { it.staged })
            assertEquals("draft", file.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun applyingAnOlderSelectionUsesItsOriginalObjectAfterAnotherStashIsCreated() = runBlocking {
        val root = Files.createTempDirectory("droide-selected-stash-").toFile()
        try {
            val git = GitManager(root)
            git.init()
            git.configureIdentity("Droide Test", "test@example.invalid")
            val note = root.resolve("note.txt")
            note.writeText("base")
            git.stage(note.name)
            git.commitStaged("base")
            note.writeText("draft A")
            git.stash()
            val selected = git.stashes().single()
            note.writeText("draft B")
            git.stash()
            assertEquals(40, selected.id.length)
            assertTrue(git.applyStash(selected).startsWith("Applied"))
            assertEquals("draft A", note.readText())
            assertEquals(2, git.stashes().size)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private class RecordingAuthority : WorkspaceDocumentAuthority {
        var dirty = false
        var reconciled = false
        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = if (!dirty) emptyList() else listOf(
            WorkspaceDocumentSnapshot("README.md", "unsaved\n", true, WorkspaceDocumentKind.TEXT, true, true, 1, 1, 0, 0, 5)
        )
        override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation {
            reconciled = true
            return WorkspacePersistedReconciliation(refreshed = listOf("README.md"))
        }
    }
}
