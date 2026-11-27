package com.baystudio.droide.ui

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.baystudio.droide.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = Application::class)
class SettingsDonationGalleryTest {
    @Test fun failedSaveIsRemovedAndRetryPublishesTheExactPackagedImage() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = Robolectric.setupContentProvider(Gallery::class.java, MediaStore.AUTHORITY)
        store.acceptPublish = false
        assertTrue(runCatching { saveDonationQrToGallery(app) }.isFailure)
        assertEquals(1, store.deletes)
        assertEquals(0, store.published)

        store.acceptPublish = true
        saveDonationQrToGallery(app)
        assertEquals(1, store.deletes)
        assertEquals(1, store.published)
        val packaged = app.resources.openRawResource(R.raw.donation_qris).use { it.readBytes() }
        assertArrayEquals(packaged, store.bytes.toByteArray())
    }

    class Gallery : ContentProvider() {
        var acceptPublish = true
        var published = 0
        var deletes = 0
        val bytes = ByteArrayOutputStream()

        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            assertEquals(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), uri)
            val metadata = requireNotNull(values)
            assertEquals("Pictures/Droide", metadata.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
            assertEquals("Droide-QRIS.png", metadata.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
            assertEquals("image/png", metadata.getAsString(MediaStore.MediaColumns.MIME_TYPE))
            assertEquals(1, metadata.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            bytes.reset()
            val row = Uri.withAppendedPath(uri, "7")
            shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerOutputStreamSupplier(row) { bytes }
            return row
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            assertEquals(0, values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            if (!acceptPublish) return 0
            published++
            return 1
        }

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deletes++
            return 1
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
    }
}
