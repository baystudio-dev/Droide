package com.baystudio.droide

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.ApplicationWorkAdmission
import com.baystudio.droide.ui.DroideTheme

/** Owns the system installer handoff across rotation; never opens the device bridge. */
class AndroidAppInstallActivity : ComponentActivity() {
    private enum class Phase { READY, PERMISSION, COMMITTED, CONFIRMING, FINISHED }
    private var phase = Phase.READY
    private var sessionId = -1
    private var targetPackage = ""
    private var epoch = ""
    private var status by mutableStateOf("Preparing installation…")
    private val installer get() = packageManager.packageInstaller
    private var callback: PendingIntent? = null
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (phase == Phase.PERMISSION) safely {
            check(packageManager.canRequestPackageInstalls()) { "Installation was not allowed. The built APK is still available in your project." }
            commit()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getIntExtra(EXTRA_ID, -1)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        epoch = intent.getStringExtra(EXTRA_EPOCH).orEmpty()
        phase = savedInstanceState?.getString("phase")?.let { value -> Phase.entries.firstOrNull { it.name == value } } ?: Phase.READY
        onBackPressedDispatcher.addCallback(this) { finish() }
        setContent {
            DroideTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), Arrangement.spacedBy(16.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(status)
                        TextButton(onClick = { finish() }) { Text("Cancel") }
                    }
                }
            }
        }
        safely {
            require(sessionId >= 0 && targetPackage.isNotBlank()) { "Installation request is unavailable. Tap Run again." }
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "This Run request has ended" }
            callback = statusCallback(this, sessionId, targetPackage, epoch)
            if (intent.action == ACTION_RESULT && savedInstanceState == null) receiveResult(intent)
            else if (phase == Phase.READY) {
                requireOwnedSession()
                if (packageManager.canRequestPackageInstalls()) commit()
                else {
                    phase = Phase.PERMISSION
                    status = "Allow Droide to install your app in Android settings"
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
            } else status = "Waiting for Android installation…"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        safely {
            require(intent.getIntExtra(EXTRA_ID, -1) == sessionId && intent.getStringExtra(EXTRA_EPOCH) == epoch) {
                "Installation result belongs to a different Run request"
            }
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "This Run request has ended" }
            if (phase != Phase.FINISHED && intent.action == ACTION_RESULT) receiveResult(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("phase", phase.name)
        super.onSaveInstanceState(outState)
    }

    private fun requireOwnedSession() {
        val session = installer.getSessionInfo(sessionId) ?: error("Installation expired. Tap Run to rebuild and try again.")
        check(session.installerPackageName == packageName && session.appPackageName == targetPackage) {
            "Installation request does not match the built app"
        }
    }

    private fun commit() {
        check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "This Run request has ended" }
        requireOwnedSession()
        phase = Phase.COMMITTED
        status = "Waiting for Android installation…"
        installer.openSession(sessionId).use { it.commit(requireNotNull(callback).intentSender) }
    }

    private fun receiveResult(result: Intent) {
        require(result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == sessionId) { "Installation session changed" }
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                requireOwnedSession()
                @Suppress("DEPRECATION")
                val confirmation = if (Build.VERSION.SDK_INT >= 33) result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                checkNotNull(confirmation) { "Android did not provide an installation confirmation" }
                phase = Phase.CONFIRMING
                startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                check(result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) == targetPackage) { "Installed app does not match this Run request" }
                phase = Phase.FINISHED
                val launch = packageManager.getLaunchIntentForPackage(targetPackage)
                if (launch != null) startActivity(launch)
                else Toast.makeText(this, "App installed. This APK has no launcher activity.", Toast.LENGTH_LONG).show()
                finish()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish()
            else -> error("Android could not install the APK: " + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(500))
        }
    }

    private fun safely(action: () -> Unit) {
        try { action() }
        catch (failure: Exception) {
            Toast.makeText(this, failure.message ?: "Could not install the app", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            callback?.cancel()
            if (phase != Phase.FINISHED && sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
        }
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_ID = "droide.install.session"
        internal const val EXTRA_PACKAGE = "droide.install.package"
        internal const val EXTRA_EPOCH = "droide.install.epoch"
        internal const val ACTION_RESULT = "com.baystudio.droide.action.APP_INSTALL_RESULT"

        internal fun intent(context: Context, id: Int, target: String, epoch: String): Intent =
            Intent(context, AndroidAppInstallActivity::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_PACKAGE, target).putExtra(EXTRA_EPOCH, epoch)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        internal fun statusCallback(context: Context, id: Int, target: String, epoch: String): PendingIntent {
            val result = intent(context, id, target, epoch).setAction(ACTION_RESULT)
                .setData(Uri.parse("droide-install://$epoch/$id"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle() else null
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getActivity(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT or mutable, options)
        }
    }
}
