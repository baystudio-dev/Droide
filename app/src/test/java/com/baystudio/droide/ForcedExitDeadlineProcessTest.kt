package com.baystudio.droide

import android.os.Looper
import com.baystudio.droide.core.ApplicationWorkAdmission
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowProcess
import java.io.File
import java.util.concurrent.TimeUnit

/** Separate process fixture: a previous successful Exit must not preinitialize the export journal. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DroideApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ForcedExitDeadlineProcessTest {
    @Test fun failedCleanupIsTerminatedAfterFiveSecondsWithoutMainLooperProgress() {
        val app = RuntimeEnvironment.getApplication() as DroideApplication
        ShadowApplication.setProcessName(app.packageName)
        app.applicationInfo.processName = app.packageName
        ShadowProcess.setPid(4243)
        File(app.filesDir, "exports-v1").writeText("not a directory")
        val epoch = ApplicationWorkAdmission.reopenFromUser()
        val started = System.nanoTime()
        app.requestExit(epoch, Any())
        while (app.exitState.value.failure == null && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5)) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertNotNull(app.exitState.value.toString(), app.exitState.value.failure)
        assertFalse(ShadowProcess.wasKilled(4243))
        // Stop pumping Main: the watchdog remains independent of the frozen UI/cleanup scope.
        while (!ShadowProcess.wasKilled(4243) && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(7)) Thread.sleep(20)
        assertTrue("Exit must terminate even when cleanup never succeeds", ShadowProcess.wasKilled(4243))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Forced Exit observed at ${elapsedMs}ms", elapsedMs in 5_000L..7_000L)
        assertFalse("Forced exit cannot claim recovery succeeded", app.exitState.value.completed)
        assertFalse(ApplicationWorkAdmission.isOpen)
    }
}
