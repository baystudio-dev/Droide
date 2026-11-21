package com.baystudio.droide.ui

import android.text.format.Formatter
import android.webkit.WebView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.baystudio.droide.R
import com.baystudio.droide.core.AgentBrowserController
import com.baystudio.droide.core.BrowserBlobDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BrowserDownloadPrompt(controller: AgentBrowserController, view: WebView?, presented: Boolean, onStatus: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberWorkspaceCoroutineScope()
    val request by controller.pendingDownload.collectAsState()
    var transfer by remember(view) { mutableStateOf<Job?>(null) }
    DisposableEffect(view) { onDispose { transfer?.cancel() } }
    if (presented && transfer?.isActive == true) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.browser_download_saving), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { transfer?.cancel() }) { Text(stringResource(android.R.string.cancel)) }
        }
        return
    }
    val offer = request ?: return
    if (!presented || view == null) return
    val file = offer.file
    val details = listOfNotNull(file.fileName, file.host, file.length.takeIf { it >= 0 }?.let { Formatter.formatFileSize(context, it) })
        .joinToString("\n")
    AlertDialog(onDismissRequest = { controller.dismissDownload(offer) },
        title = { Text(stringResource(R.string.browser_download_title)) }, text = { Text(details) },
        dismissButton = { TextButton(onClick = { controller.dismissDownload(offer) }) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            if (controller.acceptDownload(offer, view)) {
                transfer = scope.launch(Dispatchers.Main.immediate) {
                    try {
                        file.save(context) { output ->
                            BrowserBlobDownload.copy(view, file, output) { controller.isReviewIdentityCurrent(offer.document) }
                        }
                        onStatus(context.getString(R.string.browser_download_saved, file.fileName))
                    } catch (_: TimeoutCancellationException) {
                        onStatus(context.getString(R.string.browser_download_timeout))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        val code = failure.message?.takeIf { it.matches(Regex("Download returned HTTP [0-9]{3}")) }
                        val message = context.getString(R.string.browser_download_failed)
                        onStatus(listOfNotNull(message, code).joinToString(" · "))
                    } finally { transfer = null }
                }
            }
        }) { Text(stringResource(R.string.browser_download_save)) } })
}
