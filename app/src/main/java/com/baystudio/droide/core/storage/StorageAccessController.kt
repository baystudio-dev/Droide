package com.baystudio.droide.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import com.baystudio.droide.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class StorageAccessController internal constructor(context: Context) : StorageAccessSource {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("device-storage", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(StorageAccessSnapshot(false, false, false, emptyList()))
    val state = mutableState.asStateFlow()
    private var lastRefreshNanos = 0L

    @Synchronized
    override fun refresh(): StorageAccessSnapshot {
        val supported = BuildConfig.DEVICE_STORAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val enabled = preferences.getBoolean("enabled", false)
        val granted = supported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val volumes = if (supported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && enabled && granted) {
            context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { volume ->
                val state = volume.state
                val path = volume.directory?.absolutePath ?: return@mapNotNull null
                if (state !in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY) ||
                    !ExternalMountPlan.validVolumePath(path)) return@mapNotNull null
                SharedStorageVolume(path, volume.getDescription(context), volume.isPrimary,
                    state == Environment.MEDIA_MOUNTED)
            }.distinctBy { it.path }.sortedBy { it.path }
        } else emptyList()
        val before = mutableState.value
        val next = StorageAccessSnapshot(supported, enabled, granted, volumes, before.generation)
        return (if (next == before) before else next.copy(generation = before.generation + 1))
            .also { mutableState.value = it; lastRefreshNanos = System.nanoTime() }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): StorageAccessSnapshot {
        check(!enabled || (BuildConfig.DEVICE_STORAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)) {
            "This build or Android version uses the system file picker instead"
        }
        check(preferences.edit().putBoolean("enabled", enabled).commit()) { "Could not save storage preference" }
        return refresh()
    }

    fun permissionIntent(): Intent {
        check(BuildConfig.DEVICE_STORAGE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val specific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"))
        return if (specific.resolveActivity(context.packageManager) != null) specific
            else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    @Synchronized
    override fun requireCurrent(snapshot: StorageAccessSnapshot) {
        val observed = if (System.nanoTime() - lastRefreshNanos >= 250_000_000L) refresh() else mutableState.value
        check(observed == snapshot && snapshot.available) { "Device storage access changed; refresh and retry" }
    }

    companion object {
        @Volatile private var instance: StorageAccessController? = null
        fun get(context: Context): StorageAccessController = instance ?: synchronized(this) {
            instance ?: StorageAccessController(context).also { instance = it }
        }
    }
}
