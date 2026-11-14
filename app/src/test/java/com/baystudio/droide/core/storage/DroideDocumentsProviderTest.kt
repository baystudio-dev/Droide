package com.baystudio.droide.core.storage

import android.content.Context
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.baystudio.droide.core.PathSecurity
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DroideDocumentsProviderTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var store: SharedWorkspaceStore
    private lateinit var provider: DroideDocumentsProvider
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("shared-workspaces", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.filesDir, "projects/provider-test").apply { mkdirs() }
        store = SharedWorkspaceStore(context)
        provider = DroideDocumentsProvider().apply { attachInfo(this@DroideDocumentsProviderTest.context, ProviderInfo().apply {
            authority = "${this@DroideDocumentsProviderTest.context.packageName}.documents"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        }) }
    }
    @After fun cleanup() { PathSecurity.deleteTreeNoFollow(File(context.filesDir, "projects")) }

    @Test fun coldRootQueryDoesNotExposeProjectsWithoutUserOptIn() {
        provider.queryRoots(null).use { assertEquals(0, it.count) }
        assertFalse(File(context.filesDir, "guest").exists())
        store.setShared(root, "Fixture", true)
        provider.queryRoots(null).use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            assertEquals("Droide · Fixture", it.getString(it.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_TITLE)))
        }
    }

    @Test fun sharedListingsExcludeStateSecretsAndSymlinksAndRemainReadOnly() {
        File(root, "visible.txt").writeText("saved bytes")
        File(root, ".env").writeText("secret")
        File(root, ".droide").mkdir()
        Files.createSymbolicLink(File(root, "alias").toPath(), File(root, "visible.txt").toPath())
        store.setShared(root, "Fixture", true)
        val id = store.entries().single().id
        provider.queryChildDocuments(SharedDocumentIds.encode(id, ""), null, sortOrder = null).use { cursor ->
            assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst())
            assertEquals("visible.txt", cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)))
        }
        val file = SharedDocumentIds.encode(id, "visible.txt")
        for (mode in listOf("w", "rw", "rwt", "wa")) {
            assertThrows(FileNotFoundException::class.java) { provider.openDocument(file, mode, null) }
        }
        assertEquals("saved bytes", File(root, "visible.txt").readText())
        assertThrows(FileNotFoundException::class.java) { provider.openDocument(SharedDocumentIds.encode(id, "alias"), "r", null) }
    }

    @Test fun revokingShareRejectsPreviouslyValidDocumentIds() {
        store.setShared(root, "Fixture", true)
        val id = SharedDocumentIds.encode(store.entries().single().id, "visible.txt")
        store.setShared(root, "Fixture", false)
        assertThrows(FileNotFoundException::class.java) { provider.queryDocument(id, null) }
    }
}
