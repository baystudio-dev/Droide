package com.baystudio.droide.core.storage

import android.content.Context
import android.os.Environment
import android.os.UserHandle
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import com.baystudio.droide.BuildConfig
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, shadows = [StorageEnvironmentShadow::class])
class StorageAccessControllerTest {
    private lateinit var context: Context
    private lateinit var controller: StorageAccessController
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("device-storage", Context.MODE_PRIVATE).edit().clear().commit()
        controller = StorageAccessController(context)
        StorageEnvironmentShadow.granted = false
        shadowOf(context.getSystemService(StorageManager::class.java)).resetStorageVolumeList()
    }

    @Test fun userChoiceAndOsGrantAreIndependentAndNeverSelfGranted() {
        val initial = controller.refresh()
        assertFalse(initial.enabled); assertFalse(initial.available)
        if (!BuildConfig.DEVICE_STORAGE) {
            assertFalse(initial.supported)
            assertThrows(IllegalStateException::class.java) { controller.setEnabled(true) }
            return
        }
        val requested = controller.setEnabled(true)
        assertTrue(requested.enabled); assertFalse(requested.granted)
        StorageEnvironmentShadow.granted = true
        val active = controller.refresh()
        assertTrue(active.available)
        assertTrue(active.generation > requested.generation)
        assertEquals(active, controller.refresh())
        val disabled = controller.setEnabled(false)
        assertFalse(disabled.available); assertTrue(disabled.granted)
    }

    @Test fun volumeMetadataUsesActualAndroidUserAndUnmountingChangesGeneration() {
        if (!BuildConfig.DEVICE_STORAGE) { assertFalse(controller.refresh().supported); return }
        StorageEnvironmentShadow.granted = true
        val manager = shadowOf(context.getSystemService(StorageManager::class.java))
        manager.addStorageVolume(StorageVolumeBuilder("primary", File("/storage/emulated/10"), "Shared", UserHandle.getUserHandleForUid(1_000_000), Environment.MEDIA_MOUNTED)
            .setIsPrimary(true).setIsEmulated(true).build())
        manager.addStorageVolume(StorageVolumeBuilder("card", File("/storage/A123-4567"), "SD", UserHandle.getUserHandleForUid(1_000_000), Environment.MEDIA_MOUNTED_READ_ONLY)
            .setIsPrimary(false).setIsRemovable(true).build())
        val active = controller.setEnabled(true)
        assertEquals(setOf("/storage/emulated/10", "/storage/A123-4567"), active.volumes.map { it.path }.toSet())
        assertFalse(active.volumes.single { !it.primary }.writable)
        manager.resetStorageVolumeList()
        val removed = controller.refresh()
        assertTrue(removed.volumes.isEmpty())
        assertTrue(removed.generation > active.generation)
        assertThrows(IllegalStateException::class.java) { controller.requireCurrent(active) }
    }

    @Test @Config(sdk = [29]) fun androidTenKeepsPickerRouteWithoutLegacyStorageRequest() {
        StorageEnvironmentShadow.granted = true
        val state = controller.refresh()
        assertFalse(state.supported); assertFalse(state.granted)
        assertTrue(state.volumes.isEmpty())
        assertThrows(IllegalStateException::class.java) { controller.setEnabled(true) }
    }
}

@Implements(Environment::class)
class StorageEnvironmentShadow : ShadowEnvironment() {
    companion object {
        @JvmField var granted = false
        @JvmStatic @Implementation(minSdk = 30) fun isExternalStorageManager() = granted
    }
}
