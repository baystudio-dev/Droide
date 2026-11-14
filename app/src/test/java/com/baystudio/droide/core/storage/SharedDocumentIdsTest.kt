package com.baystudio.droide.core.storage

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharedDocumentIdsTest {
    private val workspace = UUID.randomUUID().toString()
    @Test fun unicodeNamesRoundTripWithoutUsingAbsolutePathsAsIds() {
        val path = "hasil kerja/日本語 😀.pdf"
        val id = SharedDocumentIds.encode(workspace, path)
        assertEquals(path, SharedDocumentIds.decode(id).relative)
        assertFalse(id.contains(path))
    }
    @Test fun traversalPrivateStateAndSensitiveFilesAreNotShared() {
        for (path in listOf("/data/private", "../outside", "folder/../file", ".droide/chats.db", "x/.droide/token", ".git/config", ".ssh/id_rsa", "bad\\path", "bad\npath", ".env")) {
            assertFalse(path, SharedDocumentIds.visible(path))
            assertThrows(IllegalArgumentException::class.java) { SharedDocumentIds.encode(workspace, path) }
        }
    }
    @Test fun childChecksUseSegmentsAndWorkspaceIdentity() {
        val parent = SharedDocumentIds.encode(workspace, "app")
        assertTrue(SharedDocumentIds.isChild(parent, SharedDocumentIds.encode(workspace, "app/main.kt")))
        assertFalse(SharedDocumentIds.isChild(parent, parent))
        assertFalse(SharedDocumentIds.isChild(parent, SharedDocumentIds.encode(workspace, "apple/main.kt")))
        assertFalse(SharedDocumentIds.isChild(parent, SharedDocumentIds.encode(UUID.randomUUID().toString(), "app/main.kt")))
    }
    @Test fun oldAbsoluteAndMalformedIdsAreRejected() {
        for (id in listOf("/data/user/files", "$workspace:***", "$workspace:YS8uLi9i", "$workspace:Zm9v=")) {
            assertThrows(IllegalArgumentException::class.java) { SharedDocumentIds.decode(id) }
        }
    }
}
