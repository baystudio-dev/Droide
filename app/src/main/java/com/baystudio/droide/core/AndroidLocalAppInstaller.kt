package com.baystudio.droide.core

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import com.baystudio.droide.AndroidAppInstallActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class AndroidLocalAppInstaller(context: Context) {
    private val context = context.applicationContext
    private val installer = this.context.packageManager.packageInstaller

    suspend fun install(apk: File) {
        val epoch = ApplicationWorkAdmission.requireOpen()
        AndroidRuntimeApkSnapshot(File(context.filesDir, "runtime-tmp")).withSnapshot(apk) { snapshot ->
            val packageName = context.packageManager.getPackageArchiveInfo(snapshot.path, 0)?.packageName
                ?.let(AndroidApkInspectionPolicy::packageName)
                ?: error("The build did not produce a readable Android APK")
            val id = installer.createSession(parameters(packageName, snapshot.length()))
            var handedOff = false
            var failure: Throwable? = null
            try {
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, snapshot.length()).use { output ->
                        snapshot.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        session.fsync(output)
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    ApplicationWorkAdmission.withOpen(epoch) {
                        context.startActivity(AndroidAppInstallActivity.intent(context, id, packageName, epoch))
                        handedOff = true
                    }
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!handedOff) try { installer.abandonSession(id) }
                catch (cleanup: Exception) { failure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }

    internal fun parameters(packageName: String, bytes: Long) =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(bytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        }
}
