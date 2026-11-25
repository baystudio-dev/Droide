package com.baystudio.droide.core

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.DroideStashDrop
import org.eclipse.jgit.internal.storage.file.RefDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitStashSelectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun staleOrLockedSelectionPreservesAllStashesAndPackedLastStashCanBeDropped() {
        val root = temporary.newFolder()
        Git.init().setDirectory(root).call().use { git ->
            val note = File(root, "note.txt")
            git.repository.config.apply { setString("user", null, "name", "Test"); setString("user", null, "email", "test@example.invalid"); save() }
            note.writeText("base")
            git.add().addFilepattern(note.name).call()
            git.commit().setMessage("base").call()
            fun stash(text: String): String {
                note.writeText(text)
                return git.stashCreate().setWorkingDirectoryMessage(text).call().name
            }
            fun ids() = git.stashList().call().map { it.name }
            val a = stash("A")
            val b = stash("B")
            val c = stash("C")
            assertThrows(java.io.IOException::class.java) { DroideStashDrop.drop(git.repository, 0, a) }
            val lock = File(git.repository.directory, "refs/stash.lock")
            assertTrue(lock.createNewFile())
            try {
                assertThrows(java.io.IOException::class.java) { DroideStashDrop.drop(git.repository, 2, a) }
                assertEquals(listOf(c, b, a), ids())
            } finally { assertTrue(lock.delete()) }
            DroideStashDrop.drop(git.repository, 1, b)
            assertEquals(listOf(c, a), ids())
            DroideStashDrop.drop(git.repository, 0, c)
            assertEquals(listOf(a), ids())
            (git.repository.refDatabase as RefDirectory).pack(listOf("refs/stash"))
            DroideStashDrop.drop(git.repository, 0, a)
            assertNull(git.repository.exactRef("refs/stash"))
            assertTrue(ids().isEmpty())
        }
    }
}
