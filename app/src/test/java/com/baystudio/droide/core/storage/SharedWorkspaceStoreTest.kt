package com.baystudio.droide.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.baystudio.droide.core.PathSecurity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SharedWorkspaceStoreTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: SharedWorkspaceStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("shared-workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.filesDir, "projects/shared-test").apply { mkdirs() }
        store = SharedWorkspaceStore(context)
    }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(File(context.filesDir, "projects")) }

    @Test fun sharingIsOptInAndRevocationInvalidatesOldDocumentIds() {
        assertTrue(store.entries().isEmpty())
        store.setShared(root, "My project", true)
        val first = store.entries().single()
        store.setShared(root, "My project", false)
        assertTrue(store.entries().isEmpty())
        store.setShared(root, "My project", true)
        assertNotEquals(first.id, store.entries().single().id)
    }

    @Test fun replacedOrSymlinkedWorkspaceCannotReuseSharing() {
        store.setShared(root, "Project", true)
        Files.move(root.toPath(), File(root.parentFile, "old-root").toPath())
        root.mkdir()
        assertTrue(store.entries().isEmpty())
        root.delete()
        Files.createSymbolicLink(root.toPath(), File(root.parentFile, "old-root").toPath())
        assertTrue(store.entries().isEmpty())
    }

    @Test fun privateAppRootAndProjectsContainerCannotBeShared() {
        for (path in listOf(context.filesDir, root.parentFile!!)) {
            assertThrows(IllegalArgumentException::class.java) { store.setShared(path, "invalid", true) }
        }
    }
}
