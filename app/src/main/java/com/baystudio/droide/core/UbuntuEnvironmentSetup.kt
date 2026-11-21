package com.baystudio.droide.core

import android.content.Context
import android.util.Log
import com.baystudio.droide.BuildConfig
import com.baystudio.droide.R
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.PosixFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EnvironmentSetupRequired : IllegalStateException(
    "Install the Ubuntu environment from the setup screen first. Your files remain available in the editor.",
)

internal object UbuntuEnvironmentSetup {
    private const val PREFERENCES = "droide_environment_setup_v1"
    private const val CONSENT = "canonical_archive_sha256"
    private const val VERIFIED_GUEST = "verified_guest_identity_v1"
    private const val DEFERRED = "setup_deferred"
    private val requests = MutableStateFlow(0L)
    private val pendingRuntimeConfirmation = AtomicBoolean(false)

    fun requireUserSetup() {
        synchronized(requests) { requests.value++ }
        throw EnvironmentSetupRequired()
    }

    fun requireConsent(context: Context) {
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(CONSENT, null) ==
            FoundryUbuntuBaseManifest.ROOTFS_SHA256) { "Ubuntu download has not been approved" }
    }

    private fun attributes(file: File): PosixFileAttributes? = try {
        Files.readAttributes(file.toPath(), PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun retainedDirectory(path: File): Boolean {
        val entry = attributes(path) ?: return false
        check(entry.isDirectory && !entry.isSymbolicLink) { "Existing Ubuntu data needs review; it was not replaced." }
        return true
    }

    private fun activeIdentity(guest: File): String? {
        val identity = StringBuilder("v2|${FoundryUbuntuGuestEnvironmentSpec.ID}")
        val rootfs = guest.resolve("rootfs")
        val bin = rootfs.resolve("usr/bin")
        for (directory in listOf(guest, rootfs, bin.parentFile!!, bin)) {
            val entry = attributes(directory) ?: return null
            if (!entry.isDirectory) return null
            // Android can report directory mtime as creationTime. Installing a package changes it.
            identity.append('|').append(entry.fileKey() ?: entry.creationTime())
        }
        val launcher = attributes(guest.resolve("launch")) ?: return null
        if (!launcher.isRegularFile || launcher.size() !in 1..65_536) return null
        val lock = guest.resolve("BOOTSTRAP_LOCK.txt")
        val linkStore = rootfs.resolve(".l2s")
        for (file in listOf(lock, bin.resolve("apt-get"), bin.resolve("dpkg"), bin.resolve("bash"))) {
            val entry = (if (file == lock) attributes(lock) else ubuntuBinaryAttributes(file.toPath(), linkStore.toPath())) ?: return null
            if (!entry.isRegularFile || entry.size() == 0L || (file == lock && entry.size() > 65_536)) return null
            identity.append('|').append(entry.fileKey() ?: entry.creationTime())
                .append('/').append(entry.size()).append('/').append(entry.lastModifiedTime())
                .append('/').append(entry.permissions().sumOf { 1 shl it.ordinal })
        }
        return identity.toString()
    }

    internal fun confirmActivation(context: Context, guest: File) {
        val identity = checkNotNull(activeIdentity(guest)) { "Ubuntu activation could not be confirmed" }
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(VERIFIED_GUEST, null) == identity) return
        check(preferences.edit().putString(VERIFIED_GUEST, identity).commit()) { "Unable to confirm Ubuntu setup" }
    }

    internal fun confirmPendingActivation(context: Context, guest: File) {
        if (!pendingRuntimeConfirmation.compareAndSet(true, false)) return
        try {
            confirmActivation(context, guest)
        } catch (failure: Exception) {
            pendingRuntimeConfirmation.set(true)
            Log.w("DroideUbuntu", "Could not record the verified Ubuntu installation", failure)
        }
    }

    internal suspend fun inspect(
        filesDir: File, verifiedIdentity: String?, deferred: Boolean,
        verifyHealth: suspend () -> Boolean?, record: (String) -> Unit, mutationInProgress: () -> Boolean = { false },
    ): EnvironmentSetupInspection {
        val base = filesDir.canonicalFile.resolve("guest")
        if (!retainedDirectory(base)) return EnvironmentSetupInspection(false, deferred)
        val current = base.resolve(FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR)
        if (!retainedDirectory(current)) {
            val previous = base.resolve(".previous")
            val retained = retainedDirectory(previous) && retainedDirectory(previous.resolve(current.name))
            return EnvironmentSetupInspection(false, deferred, recoveryRequired = retained)
        }
        val identity = activeIdentity(current)
        if (identity == null) {
            if (verifiedIdentity != null && mutationInProgress()) {
                return EnvironmentSetupInspection(false, checkLater = true)
            }
        } else {
            if (identity == verifiedIdentity) return EnvironmentSetupInspection(true)
            val healthy = verifyHealth() ?: return EnvironmentSetupInspection(false, checkLater = true)
            if (healthy) {
                val after = activeIdentity(current)
                if (after == identity) {
                    record(identity)
                    return EnvironmentSetupInspection(true)
                }
                if (after != null) return EnvironmentSetupInspection(false, checkLater = true)
            }
        }
        return EnvironmentSetupInspection(false, deferred, recoveryRequired = true)
    }

    fun create(context: Context, scope: CoroutineScope): EnvironmentSetupController {
        val app = context.applicationContext
        val epoch = ApplicationWorkAdmission.currentEpoch
        val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val manager by lazy { FoundryUbuntuGuestEnvironmentManager(app) }
        val observedRequest = requests.value
        val controller = EnvironmentSetupController(
            scope, enabled = BuildConfig.FDROID_DISTRIBUTION,
            inspect = { withContext(Dispatchers.IO) {
                inspect(app.filesDir, preferences.getString(VERIFIED_GUEST, null), preferences.getBoolean(DEFERRED, false),
                    manager::verifyExistingForSetup, record = { identity ->
                    check(preferences.edit().putString(VERIFIED_GUEST, identity).commit()) { "Unable to confirm Ubuntu setup" }
                }, mutationInProgress = { manager.mutationInProgress }).also { if (it.checkLater) pendingRuntimeConfirmation.set(true) }
            } },
            install = { progress ->
                val job = currentCoroutineContext()[Job]!!
                val foreground = DevelopmentForegroundService.startRequired(app, "Installing Ubuntu environment", job::cancel)
                try {
                    withContext(Dispatchers.IO) {
                        check(preferences.edit().putString(CONSENT, FoundryUbuntuBaseManifest.ROOTFS_SHA256)
                            .putBoolean(DEFERRED, false).commit()) { "Unable to save installation consent" }
                    }
                    manager.installFromUser(progress)
                } catch (failure: ArtifactIntegrityException) {
                    throw IllegalStateException(app.getString(R.string.environment_setup_unverified_download), failure)
                } finally { DevelopmentForegroundService.finish(app, foreground) }
            },
            defer = { withContext(Dispatchers.IO) {
                check(preferences.edit().putBoolean(DEFERRED, true).commit()) { "Unable to save setup preference" }
            } },
            admit = { ApplicationWorkAdmission.isCurrentOpen(epoch) },
        )
        if (BuildConfig.FDROID_DISTRIBUTION) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            requests.collect { if (it != observedRequest) controller.show() }
        }
        return controller
    }
}
