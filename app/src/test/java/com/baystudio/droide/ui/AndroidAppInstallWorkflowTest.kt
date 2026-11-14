package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInstaller
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.provider.Settings
import com.baystudio.droide.AndroidAppInstallActivity
import com.baystudio.droide.core.ApplicationWorkAdmission
import com.baystudio.droide.core.ApplicationWorkAdmissionFixture
import com.baystudio.droide.core.AndroidLocalAppInstaller
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidAppInstallWorkflowTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private val context get() = RuntimeEnvironment.getApplication()
    private val installer get() = context.packageManager.packageInstaller
    private val target = "example.built.application"
    private val epoch get() = ApplicationWorkAdmission.currentEpoch

    private fun session(): Int {
        val id = installer.createSession(AndroidLocalAppInstaller(context).parameters(target, 128))
        val info = installer.getSessionInfo(id)!!
        ReflectionHelpers.setField(info, "installerPackageName", context.packageName)
        ReflectionHelpers.setField(info, "appPackageName", target)
        return id
    }

    private fun result(id: Int, status: Int) = AndroidAppInstallActivity.intent(context, id, target, epoch)
        .setAction(AndroidAppInstallActivity.ACTION_RESULT)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)
        .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, target)

    @Test fun runRequestsOnlyInstallPermissionAndDenialCannotLaunchAnExistingApp() {
        val id = session()
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        val owner = Robolectric.buildActivity(AndroidAppInstallActivity::class.java,
            AndroidAppInstallActivity.intent(context, id, target, epoch)).setup()
        val activity = owner.get()
        val request = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, request.action)
        assertEquals("package:${context.packageName}", request.data.toString())
        shadowOf(activity).nextStartedActivity
        shadowOf(activity).receiveResult(request, Activity.RESULT_CANCELED, null)
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        owner.pause().stop().destroy()
        assertNull(installer.getSessionInfo(id))
    }

    @Test fun confirmationIsNotOpenedAgainAfterRotationAndCancelReleasesSession() {
        val id = session()
        val confirm = Intent("test.android.CONFIRM_INSTALL")
        val request = result(id, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirm)
        val owner = Robolectric.buildActivity(AndroidAppInstallActivity::class.java, request).setup()
        assertEquals(confirm.action, shadowOf(owner.get()).nextStartedActivity.action)
        val saved = Bundle()
        owner.pause().saveInstanceState(saved).stop().destroy()
        assertNotNull(installer.getSessionInfo(id))
        val restored = Robolectric.buildActivity(AndroidAppInstallActivity::class.java, request).create(saved).start().restoreInstanceState(saved).resume()
        assertNull(shadowOf(restored.get()).nextStartedActivity)
        restored.get().finish()
        restored.pause().stop().destroy()
        assertNull(installer.getSessionInfo(id))
    }

    @Test fun onlyAConfirmedSuccessfulInstallLaunchesTheBuiltPackage() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target)
        shadowOf(context.packageManager).addResolveInfoForIntent(launch, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = target; name = "$target.MainActivity"; exported = true }
        })
        val id = session()
        val owner = Robolectric.buildActivity(AndroidAppInstallActivity::class.java,
            result(id, PackageInstaller.STATUS_SUCCESS)).setup()
        assertEquals(target, shadowOf(owner.get()).nextStartedActivity.component!!.packageName)
        assertTrue(owner.get().isFinishing)
        owner.pause().stop().destroy()
    }

    @Test fun failedOrCancelledInstallNeverOpensAPreviouslyInstalledVersion() {
        for (status in listOf(PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_STORAGE)) {
            val id = session()
            val owner = Robolectric.buildActivity(AndroidAppInstallActivity::class.java, result(id, status)).setup()
            assertTrue(owner.get().isFinishing)
            assertNull(shadowOf(owner.get()).nextStartedActivity)
            owner.pause().stop().destroy()
            assertNull(installer.getSessionInfo(id))
        }
    }

    @Test fun resultForAnotherSessionCannotInstallOrLaunchAnything() {
        val id = session()
        val owner = Robolectric.buildActivity(AndroidAppInstallActivity::class.java,
            result(id, PackageInstaller.STATUS_SUCCESS).putExtra(PackageInstaller.EXTRA_SESSION_ID, id + 1)).setup()
        assertTrue(owner.get().isFinishing)
        assertNull(shadowOf(owner.get()).nextStartedActivity)
        owner.pause().stop().destroy()
    }

    @Test fun delayedInstallCallbackCannotReopenWorkAfterExit() {
        val id = session()
        val request = result(id, PackageInstaller.STATUS_SUCCESS)
        ApplicationWorkAdmission.closeForExit()
        val owner = Robolectric.buildActivity(AndroidAppInstallActivity::class.java, request).setup()
        assertTrue(owner.get().isFinishing)
        assertNull(shadowOf(owner.get()).nextStartedActivity)
        owner.pause().stop().destroy()
    }

    @Test fun statusCallbacksAreExplicitMutableAndIsolatedPerSessionAndEpoch() {
        val first = AndroidAppInstallActivity.statusCallback(context, 12, target, epoch)
        val second = AndroidAppInstallActivity.statusCallback(context, 13, target, epoch)
        val later = AndroidAppInstallActivity.statusCallback(context, 12, target, "b".repeat(32))
        assertNotEquals(first, second)
        assertNotEquals(first, later)
        assertFalse(shadowOf(first).isImmutable)
        assertEquals(AndroidAppInstallActivity::class.java.name, shadowOf(first).savedIntent.component!!.className)
        assertEquals(context.packageName, shadowOf(first).savedIntent.component!!.packageName)
    }

    @Test @Config(sdk = [29]) fun androidTenCanRequestLocalInstallWithoutWirelessDebugging() {
        val id = session()
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        val owner = Robolectric.buildActivity(AndroidAppInstallActivity::class.java,
            AndroidAppInstallActivity.intent(context, id, target, epoch)).setup()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(owner.get()).nextStartedActivityForResult.intent.action)
        owner.get().finish()
        owner.pause().stop().destroy()
    }
}
